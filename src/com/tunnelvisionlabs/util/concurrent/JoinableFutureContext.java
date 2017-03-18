// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFuture.JoinableFutureSynchronizationContext;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A common context within which joinable tasks may be created and interact to avoid deadlocks.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureContext implements AutoCloseable {
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
//        [DebuggerBrowsable(DebuggerBrowsableState.Never)]
	private final ReentrantLock syncContextLock = new ReentrantLock();

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
	 * The stack of futures which synchronously blocks the main thread in the initial stage (before it yields and completeOnCurrentThread starts.)
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
	 * The ID for the main thread;
	 */
	private final long mainThreadManagedThreadId;

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
		this.mainThreadManagedThreadId = this.getMainThread().getId();
		this.underlyingSynchronizationContext = synchronizationContext != null ? synchronizationContext : SynchronizationContext.getCurrent();
	}

	/**
	 * Gets the factory which creates joinable futures that do not belong to a joinable future collection.
	 */
	@NotNull
	public final JoinableFutureFactory getFactory() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getNoMessagePumpSynchronizationContext())) {
			getSyncContextLock().lock();
			try {
				if (nonJoinableFactory == null) {
					nonJoinableFactory = createDefaultFactory();
				}

				return nonJoinableFactory;
			} finally {
				getSyncContextLock().unlock();
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
	final ReentrantLock getSyncContextLock() {
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
	 * does not have privileges to re-enter the Main thread until the {@link JoinableFutureFactory#run(Function)} call
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
				getSyncContextLock().lock();
				try {
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
				} finally {
					getSyncContextLock().unlock();
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
//                    Report.Fail("Exception thrown from OnHangDetected listener. {0}", ex);
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
//                    Report.Fail("Exception thrown from OnHangDetected listener. {0}", ex);
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
//                    Verify.FailOperation(Strings.JoinableTaskContextNodeAlreadyRegistered);
			}
		}

		return new HangNotificationRegistration(node);
	}

	/**
	 * A class that clears {@link CallContext} and {@link SynchronizationContext} async/thread statics and restores
	 * those values when this structure is disposed.
	 */
	public final class RevertRelevance implements AutoCloseable {
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
//
//        /// <summary>
//        /// Contributes data for a hang report.
//        /// </summary>
//        /// <returns>The hang report contribution. Null values should be ignored.</returns>
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1024:UsePropertiesWhereAppropriate"), System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1024:UsePropertiesWhereAppropriate")]
//        protected virtual HangReportContribution GetHangReport()
//        {
//            using (this.NoMessagePumpSynchronizationContext.Apply())
//            {
//                lock (this.SyncContextLock)
//                {
//                    XElement nodes;
//                    XElement links;
//                    var dgml = CreateTemplateDgml(out nodes, out links);
//
//                    var pendingTasksElements = this.CreateNodesForPendingTasks();
//                    var taskLabels = CreateNodeLabels(pendingTasksElements);
//                    var pendingTaskCollections = CreateNodesForJoinableTaskCollections(pendingTasksElements.Keys);
//                    nodes.Add(pendingTasksElements.Values);
//                    nodes.Add(pendingTaskCollections.Values);
//                    nodes.Add(taskLabels.Select(t => t.Item1));
//                    links.Add(CreatesLinksBetweenNodes(pendingTasksElements));
//                    links.Add(CreateCollectionContainingTaskLinks(pendingTasksElements, pendingTaskCollections));
//                    links.Add(taskLabels.Select(t => t.Item2));
//
//                    return new HangReportContribution(
//                        dgml.ToString(),
//                        "application/xml",
//                        "JoinableTaskContext.dgml");
//                }
//            }
//        }
//
//        private static XDocument CreateTemplateDgml(out XElement nodes, out XElement links)
//        {
//            return Dgml.Create(out nodes, out links)
//                .WithCategories(
//                    Dgml.Category("MainThreadBlocking", "Blocking main thread", background: "#FFF9FF7F", isTag: true),
//                    Dgml.Category("NonEmptyQueue", "Non-empty queue", background: "#FFFF0000", isTag: true));
//        }
//
//        private static ICollection<XElement> CreatesLinksBetweenNodes(Dictionary<JoinableTask, XElement> pendingTasksElements)
//        {
//            Requires.NotNull(pendingTasksElements, nameof(pendingTasksElements));
//
//            var links = new List<XElement>();
//            foreach (var joinableTaskAndElement in pendingTasksElements)
//            {
//                foreach (var joinedTask in joinableTaskAndElement.Key.ChildOrJoinedJobs)
//                {
//                    XElement joinedTaskElement;
//                    if (pendingTasksElements.TryGetValue(joinedTask, out joinedTaskElement))
//                    {
//                        links.Add(Dgml.Link(joinableTaskAndElement.Value, joinedTaskElement));
//                    }
//                }
//            }
//
//            return links;
//        }
//
//        private static ICollection<XElement> CreateCollectionContainingTaskLinks(Dictionary<JoinableTask, XElement> tasks, Dictionary<JoinableTaskCollection, XElement> collections)
//        {
//            Requires.NotNull(tasks, nameof(tasks));
//            Requires.NotNull(collections, nameof(collections));
//
//            var result = new List<XElement>();
//            foreach (var task in tasks)
//            {
//                foreach (var collection in task.Key.ContainingCollections)
//                {
//                    var collectionElement = collections[collection];
//                    result.Add(Dgml.Link(collectionElement, task.Value).WithCategories("Contains"));
//                }
//            }
//
//            return result;
//        }
//
//        private static Dictionary<JoinableTaskCollection, XElement> CreateNodesForJoinableTaskCollections(IEnumerable<JoinableTask> tasks)
//        {
//            Requires.NotNull(tasks, nameof(tasks));
//
//            var collectionsSet = new HashSet<JoinableTaskCollection>(tasks.SelectMany(t => t.ContainingCollections));
//            var result = new Dictionary<JoinableTaskCollection, XElement>(collectionsSet.Count);
//            int collectionId = 0;
//            foreach (var collection in collectionsSet)
//            {
//                collectionId++;
//                var label = string.IsNullOrEmpty(collection.DisplayName) ? "Collection #" + collectionId : collection.DisplayName;
//                var element = Dgml.Node("Collection#" + collectionId, label, group: "Expanded")
//                    .WithCategories("Collection");
//                result.Add(collection, element);
//            }
//
//            return result;
//        }
//
//        private static List<Tuple<XElement, XElement>> CreateNodeLabels(Dictionary<JoinableTask, XElement> tasksAndElements)
//        {
//            Requires.NotNull(tasksAndElements, nameof(tasksAndElements));
//
//            var result = new List<Tuple<XElement, XElement>>();
//            foreach (var tasksAndElement in tasksAndElements)
//            {
//                var pendingTask = tasksAndElement.Key;
//                var node = tasksAndElement.Value;
//                int queueIndex = 0;
//                foreach (var pendingTasksElement in pendingTask.MainThreadQueueContents)
//                {
//                    queueIndex++;
//                    var callstackNode = Dgml.Node(node.Attribute("Id").Value + "MTQueue#" + queueIndex, GetAsyncReturnStack(pendingTasksElement));
//                    var callstackLink = Dgml.Link(callstackNode, node);
//                    result.Add(Tuple.Create(callstackNode, callstackLink));
//                }
//
//                foreach (var pendingTasksElement in pendingTask.ThreadPoolQueueContents)
//                {
//                    queueIndex++;
//                    var callstackNode = Dgml.Node(node.Attribute("Id").Value + "TPQueue#" + queueIndex, GetAsyncReturnStack(pendingTasksElement));
//                    var callstackLink = Dgml.Link(callstackNode, node);
//                    result.Add(Tuple.Create(callstackNode, callstackLink));
//                }
//            }
//
//            return result;
//        }
//
//        private Dictionary<JoinableTask, XElement> CreateNodesForPendingTasks()
//        {
//            var pendingTasksElements = new Dictionary<JoinableTask, XElement>();
//            lock (this.pendingTasks)
//            {
//                int taskId = 0;
//                foreach (var pendingTask in this.pendingTasks)
//                {
//                    taskId++;
//
//                    string methodName = string.Empty;
//                    var entryMethodInfo = pendingTask.EntryMethodInfo;
//                    if (entryMethodInfo != null)
//                    {
//                        methodName = string.Format(
//                            CultureInfo.InvariantCulture,
//                            " ({0}.{1})",
//                            entryMethodInfo.DeclaringType.FullName,
//                            entryMethodInfo.Name);
//                    }
//
//                    var node = Dgml.Node("Task#" + taskId, "Task #" + taskId + methodName)
//                        .WithCategories("Task");
//                    if (pendingTask.HasNonEmptyQueue)
//                    {
//                        node.WithCategories("NonEmptyQueue");
//                    }
//
//                    if (pendingTask.State.HasFlag(JoinableTask.JoinableTaskFlags.SynchronouslyBlockingMainThread))
//                    {
//                        node.WithCategories("MainThreadBlocking");
//                    }
//
//                    pendingTasksElements.Add(pendingTask, node);
//                }
//            }
//
//            return pendingTasksElements;
//        }
//
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1031:DoNotCatchGeneralExceptionTypes")]
//        private static string GetAsyncReturnStack(JoinableTaskFactory.SingleExecuteProtector singleExecuteProtector)
//        {
//            Requires.NotNull(singleExecuteProtector, nameof(singleExecuteProtector));
//
//            var stringBuilder = new StringBuilder();
//            try
//            {
//                foreach (var frame in singleExecuteProtector.WalkAsyncReturnStackFrames())
//                {
//                    stringBuilder.AppendLine(frame);
//                }
//            }
//            catch (Exception ex)
//            {
//                // Just eat the exception so we don't crash during a hang report.
//                Report.Fail("GetAsyncReturnStackFrames threw exception: ", ex);
//            }
//
//            return stringBuilder.ToString().TrimEnd();
//        }

}
