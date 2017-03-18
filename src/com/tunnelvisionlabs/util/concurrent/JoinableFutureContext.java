// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFuture.JoinableFutureFlag;
import com.tunnelvisionlabs.util.concurrent.JoinableFuture.JoinableFutureSynchronizationContext;
import com.tunnelvisionlabs.util.concurrent.JoinableFutureFactory.SingleExecuteProtector;
import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Report;
import com.tunnelvisionlabs.util.validation.Requires;
import com.tunnelvisionlabs.util.validation.Verify;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * A common context within which joinable tasks may be created and interact to avoid deadlocks.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureContext implements HangReportContributor, Disposable {
	/**
	 * A "global" lock that allows the graph of interconnected sync context and JoinableSet instances communicate in a
	 * thread-safe way without fear of deadlocks due to each taking their own private lock and then calling others, thus
	 * leading to deadlocks from lock ordering issues.
	 *
	 * <p>Yes, global locks should be avoided wherever possible. However even MEF from the .NET Framework uses a global
	 * lock around critical composition operations because containers can be interconnected in arbitrary ways. The code
	 * in this file has a very similar problem, so we use a similar solution. Except that our lock is only as global as
	 * the {@link JoinableFutureContext}. It isn't static.</p>
	 */
	private final Object syncContextLock = new Object();

	/**
	 * An {@link AsyncLocal} value that carries the joinable instance associated with an async operation.
	 */
	private final AsyncLocal<JoinableFuture<?>> joinableOperation = new AsyncLocal<>();

	/**
	 * The set of futures that have started but have not yet completed.
	 *
	 * <p>All access to this collection should be guarded by locking this collection.</p>
	 */
	private final Set<JoinableFuture<?>> pendingTasks = new HashSet<>();

	/**
	 * The stack of futures which synchronously blocks the main thread in the initial stage (before it yields and
	 * completeOnCurrentThread starts.)
	 *
	 * <p>Normally we expect this stack contains 0 or 1 future. When a synchronous future starts another synchronous
	 * future in the initialization stage, we might get more than 1 futures, but it should be very rare to exceed 2
	 * futures. All access to this collection should be guarded by locking this collection.
	 */
	private final Deque<JoinableFuture<?>> initializingSynchronouslyMainThreadTasks = new ArrayDeque<>(2);

	/**
	 * A set of receivers of hang notifications.
	 *
	 * <p>All access to this collection should be guarded by locking this collection.</p>
	 */
	private final Set<JoinableFutureContextNode> hangNotifications = new HashSet<>();

	/**
	 * The main thread.
	 */
	private final Thread mainThread;

	/**
	 * A single joinable future factory that itself cannot be joined.
	 */
//        [DebuggerBrowsable(DebuggerBrowsableState.Never)]
	private JoinableFutureFactory nonJoinableFactory;

	/**
	 * This is the backing field for {@link #getUnderlyingSynchronizationContext()}.
	 */
	private SynchronizationContext underlyingSynchronizationContext;

	/**
	 * Constructs a new instance of the {@link JoinableFutureContext} class assuming the current thread is the main
	 * thread and {@link SynchronizationContext#getCurrent()} will provide the means to switch to the main thread from
	 * another thread.
	 */
	public JoinableFutureContext() {
		this(Thread.currentThread(), SynchronizationContext.getCurrent());
	}

	/**
	 * Constructs a new instance of the {@link JoinableFutureContext} class.
	 *
	 * @param mainThread The thread to switch to in {@link JoinableFutureFactory#switchToMainThreadAsync()}. If omitted,
	 * the current thread will be assumed to be the main thread.
	 * @param synchronizationContext The synchronization context to use to switch to the main thread.
	 */
	public JoinableFutureContext(Thread mainThread, SynchronizationContext synchronizationContext) {
		this.mainThread = mainThread != null ? mainThread : Thread.currentThread();
		this.underlyingSynchronizationContext = synchronizationContext != null ? synchronizationContext : SynchronizationContext.getCurrent();
	}

	/**
	 * Gets the factory which creates joinable futures that do not belong to a joinable future collection.
	 */
	@NotNull
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final JoinableFutureFactory getFactory() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getNoMessagePumpSynchronizationContext())) {
			synchronized (getSyncContextLock()) {
				if (nonJoinableFactory == null) {
					nonJoinableFactory = createDefaultFactory();
				}

				return nonJoinableFactory;
			}
		}
	}

	/**
	 * Gets the main thread that can be shared by futures created by this context.
	 */
	@NotNull
	public final Thread getMainThread() {
		return mainThread;
	}

	/**
	 * Gets a value indicating whether the caller is executing on the main thread.
	 */
	public final boolean isOnMainThread() {
		return Thread.currentThread() == mainThread;
	}

	/**
	 * Gets a value indicating whether the caller is currently running within the context of a joinable future.
	 *
	 * <p>Use of this property is generally discouraged, as any operation that becomes a no-op when no ambient
	 * {@link JoinableFuture} is present is very cheap. For clients that have complex algorithms that are only relevant
	 * if an ambient joinable future is present, this property may serve to skip that for performance reasons.</p>
	 */
	public final boolean isWithinJoinableFuture() {
		return getAmbientFuture() != null;
	}

	/**
	 * Gets the underlying {@link SynchronizationContext} that controls the main thread in the host.
	 */
	final SynchronizationContext getUnderlyingSynchronizationContext() {
		return underlyingSynchronizationContext;
	}

	/**
	 * Gets the context-wide synchronization lock.
	 */
	final Object getSyncContextLock() {
		return syncContextLock;
	}

	/**
	 * Gets the caller's ambient joinable future.
	 */
	@Nullable
	final JoinableFuture<?> getAmbientFuture() {
		return joinableOperation.getValue();
	}

	/**
	 * Sets the caller's ambient joinable future.
	 */
	void setAmbientFuture(JoinableFuture<?> future) {
		joinableOperation.setValue(future);
	}

	/**
	 * Gets a {@link SynchronizationContext} which, when applied, suppresses any message pump that may run during
	 * synchronous blocks of the calling thread.
	 */
	@NotNull
	protected SynchronizationContext getNoMessagePumpSynchronizationContext() {
		// Callers of this method are about to take a private lock, which tends
		// to cause a deadlock while debugging because of lock contention with the
		// debugger's expression evaluator. So prevent that.
//                Debugger.NotifyOfCrossThreadDependency();

		return NoMessagePumpSyncContext.getDefault();
	}

	/**
	 * Conceals any {@link JoinableFuture} the caller is associated with until the returned value is disposed.
	 *
	 * <p>In some cases asynchronous work may be spun off inside a delegate supplied to {@code run}, so that the work
	 * does not have privileges to re-enter the Main thread until the {@link JoinableFutureFactory#run(Supplier)} call
	 * has returned and the UI thread is idle. To prevent the asynchronous work from automatically being allowed to
	 * re-enter the Main thread, wrap the code that calls the asynchronous future in a {@code true}-with-resources block
	 * with a call to this method as the expression.</p>
	 *
	 * <pre>
	 * getJoinableFutureContext().runSynchronously(() -> {
	 *   try (RevertRelevance revert = getJoinableFutureContext().suppressRelevance()) {
	 *     var asyncOperation = Task.Run(async delegate {
	 *       // Some background work.
	 *       await this.JoinableTaskContext.SwitchToMainThreadAsync();
	 *       // Some Main thread work, that cannot begin until the outer RunSynchronously call has returned.
	 *     });
	 *   }
	 *
	 *   // Because the asyncOperation is not related to this Main thread work (it was suppressed),
	 *   // the following await *would* deadlock if it were uncommented.
	 *   ////await asyncOperation;
	 * });
	 * </pre>
	 *
	 * @return A value to close to restore visibility into the caller's associated {@link JoinableFuture}, if any.
	 */
	@NotNull
	public final RevertRelevance suppressRelevance() {
		return new RevertRelevance();
	}

	/**
	 * Gets a value indicating whether the main thread is blocked for the caller's completion.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final boolean isMainThreadBlocked() {
		JoinableFuture<?> ambientTask = this.getAmbientFuture();
		if (ambientTask != null) {
			if (ambientTask.hasMainThreadSynchronousFutureWaiting()) {
				return true;
			}

			// The JoinableTask dependent chain gives a fast way to check IsMainThreadBlocked.
			// However, it only works when the main thread tasks is in the CompleteOnCurrentThread loop.
			// The dependent chain won't be added when a synchronous task is in the initialization phase.
			// In that case, we still need to follow the descendent of the task in the initialization stage.
			// We hope the dependency tree is relatively small in that stage.
			try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getNoMessagePumpSynchronizationContext())) {
				synchronized (getSyncContextLock()) {
					Set<JoinableFuture<?>> allJoinedJobs = new HashSet<>();
					synchronized (initializingSynchronouslyMainThreadTasks) {
						// our read lock doesn't cover this collection
						for (JoinableFuture<?> initializingTask : initializingSynchronouslyMainThreadTasks) {
							if (!initializingTask.hasMainThreadSynchronousFutureWaiting()) {
								// This task blocks the main thread. If it has joined the ambient task
								// directly or indirectly, then our ambient task is considered blocking
								// the main thread.
								initializingTask.addSelfAndDescendentOrJoinedJobs(allJoinedJobs);
								if (allJoinedJobs.contains(ambientTask)) {
									return true;
								}

								allJoinedJobs.clear();
							}
						}
					}
				}
			}
		}

		return false;
	}

	/**
	 * Creates a joinable future factory that automatically adds all created futures to a collection that can be jointly
	 * joined.
	 *
	 * @param collection The collection that all futures should be added to.
	 */
	@NotNull
	public JoinableFutureFactory createFactory(@NotNull JoinableFutureCollection collection) {
		Requires.notNull(collection, "collection");
		return new JoinableFutureFactory(collection);
	}

	/**
	 * Creates a collection for in-flight joinable futures.
	 *
	 * @return A new joinable future collection.
	 */
	@NotNull
	public final JoinableFutureCollection createCollection() {
		return new JoinableFutureCollection(this);
	}

	@Override
	public void close() {
	}

	/**
	 * Invoked when a hang is suspected to have occurred involving the main thread.
	 *
	 * <p>A single hang occurrence may invoke this method multiple times, with increasing values in the
	 * {@code hangDuration} parameter.</p>
	 *
	 * @param hangDuration The duration of the current hang.
	 * @param notificationCount The number of times this hang has been reported, including this one.
	 * @param hangId A random GUID that uniquely identifies this particular hang.
	 */
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1031:DoNotCatchGeneralExceptionTypes")]
	protected void onHangDetected(Duration hangDuration, int notificationCount, UUID hangId) {
		List<JoinableFutureContextNode> listeners;
		synchronized (hangNotifications) {
			listeners = new ArrayList<>(hangNotifications);
		}

		JoinableFuture<?> blockingTask = JoinableFuture.getFutureCompletingOnThisThread();
		HangDetails hangDetails = new HangDetails(
			hangDuration,
			notificationCount,
			hangId,
			blockingTask != null ? blockingTask.getEntryMethod() : null);
		for (JoinableFutureContextNode listener : listeners) {
			try {
				listener.onHangDetected(hangDetails);
			} catch (Throwable ex) {
				// Report it in CHK, but don't throw. In a hang situation, we don't want the product
				// to fail for another reason, thus hiding the hang issue.
				Report.fail("Exception thrown from OnHangDetected listener. %s", ex);
			}
		}
	}

	/**
	 * Invoked when an earlier hang report is false alarm.
	 */
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1031:DoNotCatchGeneralExceptionTypes")]
	protected void onFalseHangDetected(Duration hangDuration, UUID hangId) {
		List<JoinableFutureContextNode> listeners;
		synchronized (hangNotifications) {
			listeners = new ArrayList<>(hangNotifications);
		}

		for (JoinableFutureContextNode listener : listeners) {
			try {
				listener.onFalseHangDetected(hangDuration, hangId);
			} catch (Throwable ex) {
				// Report it in CHK, but don't throw. In a hang situation, we don't want the product
				// to fail for another reason, thus hiding the hang issue.
				Report.fail("Exception thrown from OnHangDetected listener. %s", ex);
			}
		}
	}

	/**
	 * Creates a factory without a {@link JoinableFutureCollection}.
	 *
	 * <p>Used for initializing the {@link #getFactory()} property.</p>
	 */
	@NotNull
	protected JoinableFutureFactory createDefaultFactory() {
		return new JoinableFutureFactory(this);
	}

	/**
	 * Raised when a joinable future starts.
	 *
	 * @param future The future that has started.
	 */
	final void onJoinableFutureStarted(JoinableFuture<?> future) {
		Requires.notNull(future, "future");

		synchronized (pendingTasks) {
			boolean added = pendingTasks.add(future);
			assert added;
		}

		if (future.getState().contains(JoinableFuture.JoinableFutureFlag.SYNCHRONOUSLY_BLOCKING_MAIN_THREAD)) {
			synchronized (initializingSynchronouslyMainThreadTasks) {
				initializingSynchronouslyMainThreadTasks.push(future);
			}
		}
	}

	/**
	 * Raised when a joinable future completes.
	 *
	 * @param future The completing future.
	 */
	final void onJoinableFutureCompleted(@NotNull JoinableFuture<?> future) {
		Requires.notNull(future, "future");

		synchronized (pendingTasks) {
			pendingTasks.remove(future);
		}
	}

	/**
	 * Raised when it starts to wait a joinable future to complete in the main thread.
	 *
	 * @param future The future requires to be completed
	 */
	final void onSynchronousJoinableFutureToCompleteOnMainThread(@NotNull JoinableFuture<?> future) {
		Requires.notNull(future, "future");

		synchronized (this.initializingSynchronouslyMainThreadTasks) {
			assert !this.initializingSynchronouslyMainThreadTasks.isEmpty();
			assert this.initializingSynchronouslyMainThreadTasks.peek() == future;
			this.initializingSynchronouslyMainThreadTasks.pop();
		}
	}

	/**
	 * Registers a node for notification when a hang is detected.
	 *
	 * @param node The instance to notify.
	 * @return A value to close to cancel registration.
	 */
	@NotNull
	final Disposable registerHangNotifications(@NotNull JoinableFutureContextNode node) {
		Requires.notNull(node, "node");
		synchronized (hangNotifications) {
			if (!hangNotifications.add(node)) {
				Verify.failOperation("JoinableFutureContextNodeAlreadyRegistered");
			}
		}

		return new HangNotificationRegistration(node);
	}

	/**
	 * A class that clears {@link CallContext} and {@link SynchronizationContext} async/thread statics and restores
	 * those values when this structure is disposed.
	 */
	public final class RevertRelevance implements Disposable {
		private final SpecializedSyncContext temporarySyncContext;
		private final JoinableFuture<?> oldJoinable;

		/**
		 * Constructs a new instance of the {@link RevertRelevance} class.
		 */
		RevertRelevance() {
			this.oldJoinable = getAmbientFuture();
			setAmbientFuture(null);

			SynchronizationContext synchronizationContext = SynchronizationContext.getCurrent();
			if (synchronizationContext instanceof JoinableFutureSynchronizationContext) {
				JoinableFutureSynchronizationContext jobSyncContext = (JoinableFutureSynchronizationContext)synchronizationContext;
				SynchronizationContext appliedSyncContext = null;
				if (jobSyncContext.isMainThreadAffinitized()) {
					appliedSyncContext = getUnderlyingSynchronizationContext();
				}

				this.temporarySyncContext = SpecializedSyncContext.apply(appliedSyncContext);
			} else {
				this.temporarySyncContext = null;
			}
		}

		/**
		 * Reverts the async local and thread static values to their original values.
		 */
		@Override
		public void close() {
			setAmbientFuture(oldJoinable);
			if (temporarySyncContext != null) {
				temporarySyncContext.close();
			}
		}
	}

	/**
	 * A value whose closing cancels hang registration.
	 */
	private static class HangNotificationRegistration implements Disposable {

		/**
		 * The node to receive notifications. May be {@code null} if {@link #close()} has already been called.
		 */
		private JoinableFutureContextNode node;

		/**
		 * Constructs a new instance of the {@link HangNotificationRegistration} class.
		 */
		HangNotificationRegistration(@NotNull JoinableFutureContextNode node) {
			Requires.notNull(node, "node");
			this.node = node;
		}

		/**
		 * Removes the node from hang notifications.
		 */
		@Override
		public void close() {
			JoinableFutureContextNode node = this.node;
			if (node != null) {
				synchronized (node.getContext().hangNotifications) {
					boolean removed = node.getContext().hangNotifications.remove(node);
					assert removed;
				}

				this.node = null;
			}
		}
	}

	/**
	 * A class to encapsulate the details of a possible hang. An instance of this {@link HangDetails} class will be
	 * passed to the {@link JoinableFutureContextNode} instances who registered the hang notifications.
	 */
	public static class HangDetails {

		private final Duration hangDuration;
		private final int notificationCount;
		private final UUID hangId;
		private final Method entryMethod;

		/**
		 * Constructs a new instance of the {@link HangDetails} class.
		 *
		 * @param hangDuration The duration of the current hang.
		 * @param notificationCount The number of times this hang has been reported, including this one.
		 * @param hangId A random GUID that uniquely identifies this particular hang.
		 * @param entryMethod The method that served as the entry point for the {@link JoinableFuture}.
		 */
		public HangDetails(Duration hangDuration, int notificationCount, UUID hangId, Method entryMethod) {
			this.hangDuration = hangDuration;
			this.notificationCount = notificationCount;
			this.hangId = hangId;
			this.entryMethod = entryMethod;
		}

		/**
		 * Gets the length of time this hang has lasted so far.
		 */
		public final Duration getHangDuration() {
			return hangDuration;
		}

		/**
		 * Gets the number of times this particular hang has been reported, including this one.
		 */
		public final int getNotificationCount() {
			return notificationCount;
		}

		/**
		 * Gets a unique UUID identifying this particular hang. If the same hang is reported multiple times (with
		 * increasing duration values) the value of this property will remain constant.
		 */
		public final UUID getHangId() {
			return hangId;
		}

		/**
		 * Gets the method that served as the entry point for the {@link JoinableFuture} that now blocks a thread.
		 *
		 * <p>The method indicated here may not be the one that is actually blocking a thread, but typically a deadlock
		 * is caused by a violation of a threading rule which is under the entry point's control. So usually regardless
		 * of where someone chooses the block a thread for the completion of a {@link JoinableFuture}, a hang usually
		 * indicates a bug in the code that created it. This value may be used to assign the hangs to different buckets
		 * based on this method info.</p>
		 */
		public final Method getEntryMethod() {
			return entryMethod;
		}
	}

//        /// <summary>
//        /// Contributes data for a hang report.
//        /// </summary>
//        /// <returns>The hang report contribution.</returns>
//        HangReportContribution IHangReportContributor.GetHangReport()
//        {
//            return this.GetHangReport();
//        }

	/**
	 * Contributes data for a hang report.
	 *
	 * @return The hang report contribution. {@code null} values should be ignored.
	 */
	@Override
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public HangReportContribution getHangReport() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getNoMessagePumpSynchronizationContext())) {
			synchronized (getSyncContextLock()) {
				try {
					StrongBox<Element> nodes = new StrongBox<>();
					StrongBox<Element> links = new StrongBox<>();
					Document dgml = createTemplateDgml(nodes, links);

					Map<JoinableFuture<?>, Element> pendingTasksElements = createNodesForPendingFutures(dgml);
					List<Map.Entry<Element, Element>> taskLabels = createNodeLabels(dgml, pendingTasksElements);
					Map<JoinableFutureCollection, Element> pendingTaskCollections = createNodesForJoinableFutureCollections(dgml, pendingTasksElements.keySet());
					for (Element child : pendingTasksElements.values()) {
						nodes.value.appendChild(child);
					}

					for (Element child : pendingTaskCollections.values()) {
						nodes.value.appendChild(child);
					}

					for (Map.Entry<Element, Element> pair : taskLabels) {
						nodes.value.appendChild(pair.getKey());
					}

					for (Element element : createsLinksBetweenNodes(pendingTasksElements)) {
						links.value.appendChild(element);
					}

					for (Element element : createCollectionContainingFutureLinks(pendingTasksElements, pendingTaskCollections)) {
						links.value.appendChild(element);
					}

					for (Map.Entry<Element, Element> pair : taskLabels) {
						links.value.appendChild(pair.getValue());
					}

					TransformerFactory transformerFactory = TransformerFactory.newInstance();
					Transformer transformer = transformerFactory.newTransformer();
					DOMSource source = new DOMSource(dgml);

					ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
					StreamResult result = new StreamResult(outputStream);
					transformer.transform(source, result);

					return new HangReportContribution(
						new String(outputStream.toByteArray(), "UTF-8"),
						"application/xml",
						"JoinableTaskContext.dgml");
				} catch (ParserConfigurationException | UnsupportedEncodingException | TransformerException ex) {
					throw new CompletionException(ex);
				}
			}
		}
	}

	private static Document createTemplateDgml(@NotNull StrongBox<Element> nodes, @NotNull StrongBox<Element> links) throws ParserConfigurationException {
		Document document = Dgml.create(nodes, links, null, null);
		return Dgml.withCategories(
			document,
			Dgml.category(document, "MainThreadBlocking", "Blocking main thread", /*background:*/ "#FFF9FF7F", null, null, /*isTag:*/ true, false),
			Dgml.category(document, "NonEmptyQueue", "Non-empty queue", /*background:*/ "#FFFF0000", null, null, /*isTag:*/ true, false));
	}

	private static Collection<Element> createsLinksBetweenNodes(@NotNull Map<JoinableFuture<?>, Element> pendingTasksElements) {
		Requires.notNull(pendingTasksElements, "pendingFuturesElements");

		List<Element> links = new ArrayList<>();
		for (Map.Entry<JoinableFuture<?>, Element> joinableTaskAndElement : pendingTasksElements.entrySet()) {
			for (JoinableFuture<?> joinedTask : joinableTaskAndElement.getKey().getChildOrJoinedJobs()) {
				Element joinedTaskElement = pendingTasksElements.get(joinedTask);
				if (joinedTaskElement != null) {
					links.add(Dgml.link(joinableTaskAndElement.getValue(), joinedTaskElement));
				}
			}
		}

		return links;
	}

	@NotNull
	private static Collection<Element> createCollectionContainingFutureLinks(@NotNull Map<JoinableFuture<?>, Element> tasks, @NotNull Map<JoinableFutureCollection, Element> collections) {
		Requires.notNull(tasks, "tasks");
		Requires.notNull(collections, "collections");

		List<Element> result = new ArrayList<>();
		for (Map.Entry<JoinableFuture<?>, Element> task : tasks.entrySet()) {
			for (JoinableFutureCollection collection : task.getKey().getContainingCollections()) {
				Element collectionElement = collections.get(collection);
				result.add(Dgml.withCategories(Dgml.link(collectionElement, task.getValue()), "Contains"));
			}
		}

		return result;
	}

	@NotNull
	private static Map<JoinableFutureCollection, Element> createNodesForJoinableFutureCollections(Document document, @NotNull Collection<JoinableFuture<?>> tasks) {
		Requires.notNull(tasks, "futures");

		Set<JoinableFutureCollection> collectionsSet = tasks.stream().flatMap(t -> t.getContainingCollections().stream()).collect(Collectors.toSet());
		Map<JoinableFutureCollection, Element> result = new HashMap<>(collectionsSet.size());
		int collectionId = 0;
		for (JoinableFutureCollection collection : collectionsSet) {
			collectionId++;
			String label = collection.getDisplayName() == null || collection.getDisplayName().isEmpty() ? "Collection #" + collectionId : collection.getDisplayName();
			Element element = Dgml.withCategories(
				Dgml.node(document, "Collection#" + collectionId, label, /*group:*/ "Expanded"),
				"Collection");
			result.put(collection, element);
		}

		return result;
	}

	private static List<Map.Entry<Element, Element>> createNodeLabels(@NotNull Document document, @NotNull Map<JoinableFuture<?>, Element> tasksAndElements) {
		Requires.notNull(tasksAndElements, "tasksAndElements");

		List<Map.Entry<Element, Element>> result = new ArrayList<>();
		for (Map.Entry<JoinableFuture<?>, Element> tasksAndElement : tasksAndElements.entrySet()) {
			JoinableFuture<?> pendingTask = tasksAndElement.getKey();
			Element node = tasksAndElement.getValue();
			int queueIndex = 0;
			for (SingleExecuteProtector<?> pendingTasksElement : pendingTask.getMainThreadQueueContents()) {
				queueIndex++;
				Element callstackNode = Dgml.node(document, node.getAttribute("Id") + "MTQueue#" + queueIndex, getAsyncReturnStack(pendingTasksElement), null);
				Element callstackLink = Dgml.link(callstackNode, node);
				result.add(new Map.Entry<Element, Element>() {
					@Override
					public Element getKey() {
						return callstackNode;
					}

					@Override
					public Element getValue() {
						return callstackLink;
					}

					@Override
					public Element setValue(Element value) {
						throw new UnsupportedOperationException("Not supported yet.");
					}

				});
			}

			for (SingleExecuteProtector<?> pendingTasksElement : pendingTask.getThreadPoolQueueContents()) {
				queueIndex++;
				Element callstackNode = Dgml.node(document, node.getAttribute("Id") + "TPQueue#" + queueIndex, getAsyncReturnStack(pendingTasksElement), null);
				Element callstackLink = Dgml.link(callstackNode, node);
				result.add(new Map.Entry<Element, Element>() {
					@Override
					public Element getKey() {
						return callstackNode;
					}

					@Override
					public Element getValue() {
						return callstackLink;
					}

					@Override
					public Element setValue(Element value) {
						throw new UnsupportedOperationException("Not supported yet.");
					}
				});
			}
		}

		return result;
	}

	@NotNull
	private Map<JoinableFuture<?>, Element> createNodesForPendingFutures(@NotNull Document document) {
		Map<JoinableFuture<?>, Element> pendingTasksElements = new HashMap<>();
		synchronized (this.pendingTasks) {
			int taskId = 0;
			for (JoinableFuture<?> pendingTask : this.pendingTasks) {
				taskId++;

				String methodName = "";
				Method entryMethodInfo = pendingTask.getEntryMethod();
				if (entryMethodInfo != null) {
					methodName = String.format(
						" (%s.%s)",
						entryMethodInfo.getDeclaringClass().getName(),
						entryMethodInfo.getName());
				}

				Element node = Dgml.withCategories(Dgml.node(document, "Task#" + taskId, "Task #" + taskId + methodName, null), "Task");
				if (pendingTask.hasNonEmptyQueue()) {
					Dgml.withCategories(node, "NonEmptyQueue");
				}

				if (pendingTask.getState().contains(JoinableFutureFlag.SYNCHRONOUSLY_BLOCKING_MAIN_THREAD)) {
					Dgml.withCategories(node, "MainThreadBlocking");
				}

				pendingTasksElements.put(pendingTask, node);
			}
		}

		return pendingTasksElements;
	}

	@NotNull
	private static String getAsyncReturnStack(@NotNull SingleExecuteProtector<?> singleExecuteProtector) {
		Requires.notNull(singleExecuteProtector, "singleExecuteProtector");

		StringBuilder stringBuilder = new StringBuilder();
		try {
			for (String frame : singleExecuteProtector.walkAsyncReturnStackFrames()) {
				stringBuilder.append(frame);
				stringBuilder.append(System.lineSeparator());
			}
		} catch (Exception ex) {
			// Just eat the exception so we don't crash during a hang report.
			Report.fail("GetAsyncReturnStackFrames threw exception: %s", ex);
		}

		return stringBuilder.toString().trim();
	}
}
