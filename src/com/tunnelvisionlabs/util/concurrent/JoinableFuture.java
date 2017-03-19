// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFutureCollection.JoinRelease;
import com.tunnelvisionlabs.util.concurrent.JoinableFutureFactory.SingleExecuteProtector;
import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Tracks asynchronous operations and provides the ability to Join those operations to avoid deadlocks while
 * synchronously blocking the main thread for the operation's completion.
 *
 * <p>For more complete comments please see the {@link JoinableFutureContext}.</p>
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFuture<T> implements Awaitable<T> {
	/**
	 * Stores the top-most {@link JoinableFuture} that is completing on the current thread, if any.
	 */
	private static final ThreadLocal<JoinableFuture<?>> COMPLETING_FUTURE = new ThreadLocal<>();

	/**
	 * The {@link JoinableFutureContext} that began the async operation.
	 */
	@NotNull
	private final JoinableFutureFactory owner;

	/**
	 * Store the future's initial creation options.
	 */
	private final Set<JoinableFutureCreationOption> creationOptions;

	/**
	 * Other instances of {@link JoinableFutureFactory} that should be posted to with any main thread bound work.
	 */
	private ListOfOftenOne<JoinableFutureFactory> nestingFactories = new ListOfOftenOne<>();

	/**
	 * The collections that this job is a member of.
	 */
	private ListOfOftenOne<JoinableFutureCollection> collectionMembership = new ListOfOftenOne<>();

	/**
	 * The future returned by the async delegate that this {@link JoinableFuture} originally executed.
	 *
	 * <p>This is {@code null} until after the async delegate returns a {@link CompletableFuture}, and retains its value
	 * even after this {@link JoinableFuture} completes.</p>
	 */
	private CompletableFuture<? extends T> wrappedTask;

	/**
	 * A map of jobs that we should be willing to poll from when we control the UI thread, and a reference count. Lazily
	 * constructed.
	 *
	 * <p>When the value in an entry is decremented to 0, the entry is removed from the map.</p>
	 */
	private Map<JoinableFuture<?>, Integer> childOrJoinedJobs;

	/**
	 * An event that is signaled when any queue in the dependent has item to process. Lazily constructed.
	 */
	private AsyncManualResetEvent queueNeedProcessEvent;

	/**
	 * The {@link #queueNeedProcessEvent} is triggered by this {@link JoinableFuture}, this allows a quick access to the
	 * event.
	 */
	private WeakReference<JoinableFuture<?>> pendingEventSource;

	/**
	 * The uplimit of the number pending events. The real number can be less because dependency can be removed, or a
	 * pending event can be processed. The number is critical, so it should only be updated in the lock region.
	 */
	private final AtomicInteger pendingEventCount = new AtomicInteger();

	/**
	 * The queue of work items. Lazily constructed.
	 */
	private ExecutionQueue mainThreadQueue;

	private ExecutionQueue threadPoolQueue;

	private final Set<JoinableFutureFlag> state = EnumSet.noneOf(JoinableFutureFlag.class);

	private JoinableFutureSynchronizationContext mainThreadJobSyncContext;

	private JoinableFutureSynchronizationContext threadPoolJobSyncContext;

	/**
	 * Store the future's initial delegate so we could show its full name in hang report.
	 */
	private Method initialDelegate;

	/**
	 * Constructs a new instance of the {@link JoinableFuture} class.
	 *
	 * @param owner The instance that began the async operation.
	 * @param synchronouslyBlocking A value indicating whether the launching thread will synchronously block for this
	 * job's completion.
	 * @param creationOptions The {@link JoinableFutureCreationOption} used to customize the future's behavior.
	 * @param initialDelegate The entry method for diagnostics.
	 */
	JoinableFuture(@NotNull JoinableFutureFactory owner, boolean synchronouslyBlocking, @NotNull Set<JoinableFutureCreationOption> creationOptions, Method initialDelegate) {
		Requires.notNull(owner, "owner");

		this.owner = owner;
		if (synchronouslyBlocking) {
			this.state.add(JoinableFutureFlag.STARTED_SYNCHRONOUSLY);
			this.state.add(JoinableFutureFlag.COMPLETING_SYNCHRONOUSLY);
		}

		if (owner.getContext().isOnMainThread()) {
			this.state.add(JoinableFutureFlag.STARTED_ON_MAIN_THREAD);
			if (synchronouslyBlocking) {
				this.state.add(JoinableFutureFlag.SYNCHRONOUSLY_BLOCKING_MAIN_THREAD);
			}
		}

		this.creationOptions = EnumSet.copyOf(creationOptions);
		this.owner.getContext().onJoinableFutureStarted(this);
		this.initialDelegate = initialDelegate;
	}

	static enum JoinableFutureFlag {
		/**
		 * This future was originally started as a synchronously executing one.
		 */
		STARTED_SYNCHRONOUSLY,
		/**
		 * This future was originally started on the main thread.
		 */
		STARTED_ON_MAIN_THREAD,
		/**
		 * This future has had its {@link #complete} method called, but has lingering continuations to execute.
		 */
		COMPLETE_REQUESTED,
		/**
		 * This future has completed.
		 */
		COMPLETE_FINALIZED,
		/**
		 * This exact future has been passed to the {@link JoinableFuture#completeOnCurrentThread} method.
		 */
		COMPLETING_SYNCHRONOUSLY,
		/**
		 * This exact future has been passed to the {@link JoinableFuture#completeOnCurrentThread} method on the main
		 * thread.
		 */
		SYNCHRONOUSLY_BLOCKING_MAIN_THREAD,
	}

	@NotNull
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	private CompletableFuture<Void> getQueueNeedProcessEvent() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				if (this.queueNeedProcessEvent == null) {
					// We pass in allowInliningWaiters: true,
					// since we control all waiters and their continuations
					// are benign, and it makes it more efficient.
					this.queueNeedProcessEvent = new AsyncManualResetEvent(/*initialState:*/false, /*allowInliningAwaiters:*/ true);
				}

				return this.queueNeedProcessEvent.waitAsync();
			}
		}
	}

	/**
	 * Gets the set of nesting factories (excluding {@link #owner}) that own {@link JoinableFuture}s that are nesting
	 * this one.
	 */
	final ListOfOftenOne<JoinableFutureFactory> getNestingFactories() {
		return nestingFactories.createCopy();
	}

	/**
	 * Sets the set of nesting factories (excluding {@link #owner}) that own {@link JoinableFuture}s that are nesting
	 * this one.
	 */
	final void setNestingFactories(@NotNull ListOfOftenOne<JoinableFutureFactory> value) {
		this.nestingFactories = value.createCopy();
	}

	/**
	 * Gets a value indicating whether the async operation represented by this instance has completed.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final boolean isDone() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				if (!isCompleteRequested()) {
					return false;
				}

				if (mainThreadQueue != null && !mainThreadQueue.isCompleted()) {
					return false;
				}

				if (threadPoolQueue != null && !threadPoolQueue.isCompleted()) {
					return false;
				}

				return true;
			}
		}
	}

	/**
	 * Gets the asynchronous future that completes when the async operation completes.
	 */
	@NotNull
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final CompletableFuture<? extends T> getFuture() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				// If this assumes ever fails, we need to add the ability to synthesize a future
				// that we'll complete when the wrapped future that we eventually are assigned completes.
				assert wrappedTask != null;
				return wrappedTask;
			}
		}
	}

	/**
	 * Gets the {@link JoinableFuture} that is completing (i.e. synchronously blocking) on this thread, nearest to the
	 * top of the call stack.
	 *
	 * <p>
	 * This property is intentionally non-public to avoid its abuse by outside callers.</p>
	 */
	@Nullable
	static JoinableFuture<?> getFutureCompletingOnThisThread() {
		return COMPLETING_FUTURE.get();
	}

	@NotNull
	final JoinableFutureFactory getFactory() {
		return this.owner;
	}

//        [DebuggerBrowsable(DebuggerBrowsableState.Never)]
	@NotNull
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final SynchronizationContext getApplicableJobSyncContext() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				if (getFactory().getContext().isOnMainThread()) {
					if (mainThreadJobSyncContext == null) {
						mainThreadJobSyncContext = new JoinableFutureSynchronizationContext(this, true);
					}

					return mainThreadJobSyncContext;
				} else if (isSynchronouslyBlockingThreadPool()) {
					if (threadPoolJobSyncContext == null) {
						threadPoolJobSyncContext = new JoinableFutureSynchronizationContext(this, false);
					}

					return threadPoolJobSyncContext;
				} else {
					// If we're not blocking the thread pool, there is no reason to use a thread pool sync context.
					return null;
				}
			}
		}
	}

	/**
	 * Gets the flags set on this future.
	 */
	@NotNull
	final EnumSet<JoinableFutureFlag> getState() {
		return EnumSet.copyOf(state);
	}

	/**
	 * Gets the future's initial creationOptions.
	 */
	@NotNull
	final Set<JoinableFutureCreationOption> getCreationOptions() {
		return creationOptions;
	}

//        #region Diagnostics collection
	/**
	 * Gets the entry method's info so we could show its full name in hang report.
	 */
	final Method getEntryMethod() {
		return initialDelegate;
	}

	/**
	 * Gets a value indicating whether this future has a non-empty queue. FOR DIAGNOSTICS COLLECTION ONLY.
	 */
	final boolean hasNonEmptyQueue() {
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());
		return (mainThreadQueue != null && !mainThreadQueue.isEmpty())
			|| (threadPoolQueue != null && !threadPoolQueue.isEmpty());
	}

	/// <summary>
	/// Gets a snapshot of all joined futures. FOR DIAGNOSTICS COLLECTION ONLY.
	/// </summary>
	final Iterable<JoinableFuture<?>> getChildOrJoinedJobs() {
		owner.getContext().getSyncContextLock();
		if (childOrJoinedJobs == null) {
			return Collections.emptyList();
		}

		return new ArrayList<>(childOrJoinedJobs.keySet());
	}

	/**
	 * Gets a snapshot of all work queued to the main thread. FOR DIAGNOSTICS COLLECTION ONLY.
	 */
	@NotNull
	final Iterable<SingleExecuteProtector<?>> getMainThreadQueueContents() {
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());
		if (mainThreadQueue == null) {
			return Collections.emptyList();
		}

		return mainThreadQueue.toList();
	}

	/**
	 * Gets a snapshot of all work queued to synchronously blocking thread pool thread. FOR DIAGNOSTICS COLLECTION ONLY.
	 */
	final Iterable<SingleExecuteProtector<?>> getThreadPoolQueueContents() {
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());
		if (threadPoolQueue == null) {
			return Collections.emptyList();
		}

		return threadPoolQueue.toList();
	}

	/**
	 * Gets the collections this task belongs to. FOR DIAGNOSTICS COLLECTION ONLY.
	 */
	@NotNull
	final Collection<JoinableFutureCollection> getContainingCollections() {
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());
		List<JoinableFutureCollection> result = new ArrayList<>();
		for (JoinableFutureCollection collection : collectionMembership) {
			result.add(collection);
		}

		return result;
	}

//        #endregion

	/**
	 * Gets a value indicating whether this task has had its {@link #complete()} method called.
	 */
//        [DebuggerBrowsable(DebuggerBrowsableState.Never)]
	private boolean isCompleteRequested() {
		return this.state.contains(JoinableFutureFlag.COMPLETE_REQUESTED);
	}

	/**
	 * Sets a value indicating that this task has had its {@link #complete()} method called.
	 */
	private void setCompleteRequested() {
		this.state.add(JoinableFutureFlag.COMPLETE_REQUESTED);
	}

	private boolean isSynchronouslyBlockingThreadPool() {
		return this.state.contains(JoinableFutureFlag.STARTED_SYNCHRONOUSLY)
			&& !this.state.contains(JoinableFutureFlag.STARTED_ON_MAIN_THREAD);
	}

	private boolean isSynchronouslyBlockingMainThread() {
		return this.state.contains(JoinableFutureFlag.STARTED_SYNCHRONOUSLY)
			&& this.state.contains(JoinableFutureFlag.STARTED_ON_MAIN_THREAD);
	}

	/**
	 * Synchronously blocks the calling thread until the operation has completed. If the caller is on the Main thread
	 * (or is executing within a {@link JoinableFuture} that has access to the main thread) the caller's access to the
	 * Main thread propagates to this {@link JoinableFuture} so that it may also access the main thread.
	 */
	public final T join() {
		return join(CancellationToken.none());
	}

	/**
	 * Synchronously blocks the calling thread until the operation has completed. If the caller is on the Main thread
	 * (or is executing within a {@link JoinableFuture} that has access to the main thread) the caller's access to the
	 * Main thread propagates to this {@link JoinableFuture} so that it may also access the main thread.
	 *
	 * @param cancellationToken A token that will exit this method before the future is completed.
	 */
	public final T join(@NotNull CancellationToken cancellationToken) {
		// We don't simply call this.CompleteOnCurrentThread because that doesn't take CancellationToken.
		// And it really can't be made to, since it sets state flags indicating the JoinableTask is
		// blocking till completion.
		// So instead, we new up a new JoinableTask to do the blocking. But we preserve the initial delegate
		// so that if a hang occurs it blames the original JoinableTask.
		owner.run(
			() -> joinAsync(cancellationToken),
			EnumSet.noneOf(JoinableFutureCreationOption.class)/*,
			this.initialDelegate*/);
		assert getFuture().isDone();
		return getFuture().join();
	}

	/**
	 * Shares any access to the main thread the caller may have. Joins any main thread affinity of the caller with the
	 * asynchronous operation to avoid deadlocks in the event that the main thread ultimately synchronously blocks
	 * waiting for the operation to complete.
	 *
	 * @return A future that completes after the asynchronous operation completes and the join is reverted.
	 */
	@NotNull
	public final CompletableFuture<T> joinAsync() {
		return joinAsync(CancellationToken.none());
	}

	/**
	 * Shares any access to the main thread the caller may have. Joins any main thread affinity of the caller with the
	 * asynchronous operation to avoid deadlocks in the event that the main thread ultimately synchronously blocks
	 * waiting for the operation to complete.
	 *
	 * @return A future that completes after the asynchronous operation completes and the join is reverted.
	 */
	@NotNull
	public final CompletableFuture<T> joinAsync(@NotNull CancellationToken cancellationToken) {
		return Async.runAsync(() -> {
			if (cancellationToken.isCancellationRequested()) {
				return Futures.completedCancelled();
			}

			return Async.usingAsync(
				ambientJobJoinsThis(),
				() -> Async.<T>awaitAsync(ThreadingTools.withCancellation(getFuture(), cancellationToken)));
		});
	}

	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final <T> void post(Consumer<T> d, T state, boolean mainThreadAffinitized) {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			SingleExecuteProtector<T> wrapper = null;
			// initialized if we should pulse it at the end of the method
			List<AsyncManualResetEvent> eventsNeedNotify = null;
			boolean postToFactory = false;

			boolean isCompleteRequested;
			boolean synchronouslyBlockingMainThread;
			synchronized (owner.getContext().getSyncContextLock()) {
				isCompleteRequested = this.isCompleteRequested();
				synchronouslyBlockingMainThread = this.isSynchronouslyBlockingMainThread();
			}

			if (isCompleteRequested) {
				// This job has already been marked for completion.
				// We need to forward the work to the fallback mechanisms.
				postToFactory = true;
			} else {
				boolean mainThreadQueueUpdated = false;
				boolean backgroundThreadQueueUpdated = false;
				wrapper = SingleExecuteProtector.create(this, d, state);

//                    if (ThreadingEventSource.Instance.IsEnabled())
//                    {
//                        ThreadingEventSource.Instance.PostExecutionStart(wrapper.GetHashCode(), mainThreadAffinitized);
//                    }
				if (mainThreadAffinitized && !synchronouslyBlockingMainThread) {
					wrapper.raiseTransitioningEvents();
				}

				synchronized (owner.getContext().getSyncContextLock()) {
					if (mainThreadAffinitized) {
						if (mainThreadQueue == null) {
							mainThreadQueue = new ExecutionQueue(this);
						}

						// Try to post the message here, but we'll also post to the underlying sync context
						// so if this fails (because the operation has completed) we'll still get the work
						// done eventually.
						this.mainThreadQueue.tryAdd(wrapper);
						mainThreadQueueUpdated = true;
					} else if (isSynchronouslyBlockingThreadPool()) {
						if (this.threadPoolQueue == null) {
							this.threadPoolQueue = new ExecutionQueue(this);
						}

						backgroundThreadQueueUpdated = this.threadPoolQueue.tryAdd(wrapper);
						if (!backgroundThreadQueueUpdated) {
							SingleExecuteProtector<T> finalWrapper = wrapper;
							ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> {
								SingleExecuteProtector.EXECUTE_ONCE.accept(finalWrapper);
							}));
						}
					} else {
						SingleExecuteProtector<T> finalWrapper = wrapper;
						ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> {
							SingleExecuteProtector.EXECUTE_ONCE.accept(finalWrapper);
						}));
					}

					if (mainThreadQueueUpdated || backgroundThreadQueueUpdated) {
						List<JoinableFuture<?>> tasksNeedNotify = this.getDependingSynchronousFutures(mainThreadQueueUpdated);
						if (!tasksNeedNotify.isEmpty()) {
							eventsNeedNotify = new ArrayList<>(tasksNeedNotify.size());
							for (JoinableFuture<?> taskToNotify : tasksNeedNotify) {
								if (taskToNotify.pendingEventSource == null || taskToNotify == this) {
									taskToNotify.pendingEventSource = new WeakReference<>(this);
								}

								taskToNotify.pendingEventCount.incrementAndGet();
								if (taskToNotify.queueNeedProcessEvent != null) {
									eventsNeedNotify.add(taskToNotify.queueNeedProcessEvent);
								}
							}
						}
					}
				}
			}

			// Notify tasks which can process the event queue.
			if (eventsNeedNotify != null) {
				for (AsyncManualResetEvent queueEvent : eventsNeedNotify) {
					queueEvent.pulseAll();
				}
			}

			// We deferred this till after we release our lock earlier in this method since we're calling outside code.
			if (postToFactory) {
				// we avoid using a wrapper in this case because this job transferring ownership to the factory.
				assert wrapper == null;
				getFactory().post(d, state, mainThreadAffinitized);
			} else if (mainThreadAffinitized) {
				// this should have been initialized in the above logic.
				assert wrapper != null;
				this.owner.postToUnderlyingSynchronizationContextOrThreadPool(wrapper);

				for (JoinableFutureFactory nestingFactory : this.nestingFactories) {
					if (nestingFactory != this.owner) {
						nestingFactory.postToUnderlyingSynchronizationContextOrThreadPool(wrapper);
					}
				}
			}
		}
	}

	/**
	 * Gets an awaiter that is equivalent to calling {@link #joinAsync}.
	 *
	 * @return An awaiter whose result is the result of the asynchronous operation.
	 */
	@NotNull
	@Override
	public final FutureAwaitable<T>.FutureAwaiter getAwaiter() {
		return new FutureAwaitable<>(joinAsync(), true).getAwaiter();
	}

	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final void setWrappedFuture(@NotNull CompletableFuture<? extends T> wrappedFuture) {
		Requires.notNull(wrappedFuture, "wrappedFuture");

		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				assert wrappedTask == null;
				this.wrappedTask = wrappedFuture;

				if (wrappedFuture.isDone()) {
					complete();
				} else {
					// Arrange for the wrapped task to complete this job when the task completes.
					this.wrappedTask.whenComplete((result, exception) -> {
						complete();
					});
				}
			}
		}
	}

	/**
	 * Fires when the underlying {@link CompletableFuture} is completed.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final void complete() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			AsyncManualResetEvent queueNeedProcessEvent = null;
			synchronized (owner.getContext().getSyncContextLock()) {
				if (!this.isCompleteRequested()) {
					this.setCompleteRequested();

					if (this.mainThreadQueue != null) {
						this.mainThreadQueue.complete();
					}

					if (this.threadPoolQueue != null) {
						this.threadPoolQueue.complete();
					}

					onQueueCompleted();

					// Always arrange to pulse the event since folks waiting
					// will likely want to know that the JoinableTask has completed.
					queueNeedProcessEvent = this.queueNeedProcessEvent;

					this.cleanupDependingSynchronousFuture();
				}
			}

			if (queueNeedProcessEvent != null) {
				// We explicitly do this outside our lock.
				queueNeedProcessEvent.pulseAll();
			}
		}
	}

	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final void removeDependency(@NotNull JoinableFuture<?> joinChild) {
		Requires.notNull(joinChild, "joinChild");

		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				if (childOrJoinedJobs != null) {
					Integer referenceCount = childOrJoinedJobs.get(joinChild);
					if (referenceCount != null) {
						if (referenceCount == 1) {
							childOrJoinedJobs.remove(joinChild);
							removeDependingSynchronousFutureFromChild(joinChild);
						} else {
							childOrJoinedJobs.put(joinChild, referenceCount - 1);
						}
					}
				}
			}
		}
	}

	/**
	 * Recursively adds this joinable and all its dependencies to the specified set, that are not yet completed.
	 */
	final void addSelfAndDescendentOrJoinedJobs(@NotNull Set<JoinableFuture<?>> joinables) {
		Requires.notNull(joinables, "joinables");

		if (!isDone()) {
			if (joinables.add(this)) {
				if (childOrJoinedJobs != null) {
					for (JoinableFuture<?> item : childOrJoinedJobs.keySet()) {
						item.addSelfAndDescendentOrJoinedJobs(joinables);
					}
				}
			}
		}
	}

	/**
	 * Runs a loop to process all queued work items, returning only when the future is completed.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final T completeOnCurrentThread() {
		assert this.wrappedTask != null;

		// "Push" this task onto the TLS field's virtual stack so that on hang reports we know which task to 'blame'.
		JoinableFuture<?> priorCompletingTask = COMPLETING_FUTURE.get();
		COMPLETING_FUTURE.set(this);
		try {
			boolean onMainThread = false;
			Set<JoinableFutureFlag> additionalFlags = EnumSet.of(JoinableFutureFlag.COMPLETING_SYNCHRONOUSLY);
			if (owner.getContext().isOnMainThread()) {
				additionalFlags.add(JoinableFutureFlag.SYNCHRONOUSLY_BLOCKING_MAIN_THREAD);
				onMainThread = true;
			}

			this.addStateFlags(additionalFlags);

			if (!isCompleteRequested()) {
//                    if (ThreadingEventSource.Instance.IsEnabled())
//                    {
//                        ThreadingEventSource.Instance.CompleteOnCurrentThreadStart(this.GetHashCode(), onMainThread);
//                    }

				try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
					synchronized (owner.getContext().getSyncContextLock()) {
						pendingEventCount.set(0);

						// Add the task to the depending tracking list of itself, so it will monitor the event queue.
						pendingEventSource = new WeakReference<>(addDependingSynchronousFuture(this, pendingEventCount));
					}
				}

				if (onMainThread) {
					owner.getContext().onSynchronousJoinableFutureToCompleteOnMainThread(this);
				}

				try {
					// Don't use IsCompleted as the condition because that
					// includes queues of posted work that don't have to complete for the
					// JoinableTask to be ready to return from the JTF.Run method.
					StrongBox<Set<JoinableFuture<?>>> visited = new StrongBox<>();
					while (!isCompleteRequested()) {
						StrongBox<SingleExecuteProtector<?>> work = new StrongBox<>();
						StrongBox<CompletableFuture<?>> tryAgainAfter = new StrongBox<>();
						if (tryPollSelfOrDependencies(onMainThread, visited, work, tryAgainAfter)) {
							work.value.tryExecute();
						} else if (tryAgainAfter.value != null) {
//                                ThreadingEventSource.Instance.WaitSynchronouslyStart();
							this.owner.waitSynchronously(tryAgainAfter.value);
//                                ThreadingEventSource.Instance.WaitSynchronouslyStop();
							assert tryAgainAfter.value.isDone();
						}
					}
				} finally {
					try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
						synchronized (owner.getContext().getSyncContextLock()) {
							// Remove itself from the tracking list, after the future is completed.
							removeDependingSynchronousFuture(this, true);
						}
					}
				}

//                    if (ThreadingEventSource.Instance.IsEnabled())
//                    {
//                        ThreadingEventSource.Instance.CompleteOnCurrentThreadStop(this.GetHashCode());
//                    }
			} else if (onMainThread) {
				owner.getContext().onSynchronousJoinableFutureToCompleteOnMainThread(this);
			}

			// Now that we're about to stop blocking a thread, transfer any work
			// that was queued but evidently not required to complete this task
			// back to the threadpool so it still gets done.
			if (threadPoolQueue != null && !threadPoolQueue.isEmpty()) {
				while (true) {
					SingleExecuteProtector<?> executor = threadPoolQueue.poll();
					if (executor == null) {
						break;
					}

					ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> SingleExecuteProtector.EXECUTE_ONCE.accept(executor)));
				}
			}

			boolean complete = this.getFuture().isDone();
			assert complete;

			// return the result or rethrow any exceptions
			return getFuture().join();
		} finally {
			COMPLETING_FUTURE.set(priorCompletingTask);
		}
	}

	final void onQueueCompleted() {
		if (isDone()) {
			// Note this code may execute more than once, as multiple queue completion
			// notifications come in.
			owner.getContext().onJoinableFutureCompleted(this);

			for (JoinableFutureCollection collection : this.collectionMembership) {
				collection.remove(this);
			}

			if (mainThreadJobSyncContext != null) {
				mainThreadJobSyncContext.onCompleted();
			}

			if (threadPoolJobSyncContext != null) {
				threadPoolJobSyncContext.onCompleted();
			}

			this.nestingFactories = new ListOfOftenOne<>();
			this.initialDelegate = null;
			this.state.add(JoinableFutureFlag.COMPLETE_FINALIZED);
		}
	}

	final void onAddedToCollection(@NotNull JoinableFutureCollection collection) {
		Requires.notNull(collection, "collection");
		collectionMembership.add(collection);
	}

	final void onRemovedFromCollection(@NotNull JoinableFutureCollection collection) {
		Requires.notNull(collection, "collection");
		collectionMembership.remove(collection);
	}

	/**
	 * Adds the specified flags to the {@link #state} field.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	private void addStateFlags(@NotNull Set<JoinableFutureFlag> flags) {
		// Try to avoid taking a lock if the flags are already set appropriately.
		if (!this.state.containsAll(flags)) {
			try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
				synchronized (owner.getContext().getSyncContextLock()) {
					this.state.addAll(flags);
				}
			}
		}
	}

	@SuppressWarnings(Suppressions.TRY_SCOPE)
	private boolean tryPollSelfOrDependencies(boolean onMainThread, @NotNull final StrongBox<Set<JoinableFuture<?>>> visited, @NotNull final StrongBox<SingleExecuteProtector<?>> work, @NotNull final StrongBox<CompletableFuture<?>> tryAgainAfter) {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				if (isDone()) {
					work.value = null;
					tryAgainAfter.value = null;
					return false;
				}

				if (this.pendingEventCount.get() > 0) {
					this.pendingEventCount.decrementAndGet();

					if (this.pendingEventSource != null) {
						JoinableFuture<?> pendingSource = pendingEventSource.get();
						if (pendingSource != null && pendingSource.isDependingSynchronousFuture(this)) {
							ExecutionQueue queue = onMainThread ? pendingSource.mainThreadQueue : pendingSource.threadPoolQueue;
							if (queue != null && !queue.isCompleted()) {
								work.value = queue.poll();
								if (work.value != null) {
									if (queue.isEmpty()) {
										this.pendingEventSource = null;
									}

									tryAgainAfter.value = null;
									return true;
								}
							}
						}

						this.pendingEventSource = null;
					}

					if (visited.value == null) {
						visited.value = new HashSet<>();
					} else {
						visited.value.clear();
					}

					if (tryPollSelfOrDependencies(onMainThread, visited.value, work)) {
						tryAgainAfter.value = null;
						return true;
					}
				}

				this.pendingEventCount.set(0);

				work.value = null;
				tryAgainAfter.value = getQueueNeedProcessEvent();
				return false;
			}
		}
	}

	private boolean tryPollSelfOrDependencies(boolean onMainThread, @NotNull Set<JoinableFuture<?>> visited, @NotNull final StrongBox<SingleExecuteProtector<?>> work) {
		Requires.notNull(visited, "visited");
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());

		// We only need find the first work item.
		work.value = null;
		if (visited.add(this)) {
			ExecutionQueue queue = onMainThread ? this.mainThreadQueue : this.threadPoolQueue;
			if (queue != null && !queue.isCompleted()) {
				work.value = queue.poll();
			}

			if (work.value == null) {
				if (childOrJoinedJobs != null && !isDone()) {
					for (JoinableFuture<?> item : this.childOrJoinedJobs.keySet()) {
						if (item.tryPollSelfOrDependencies(onMainThread, visited, work)) {
							break;
						}
					}
				}
			}
		}

		return work.value != null;
	}

	/**
	 * Adds a {@link JoinableFuture} instance as one that is relevant to the async operation.
	 *
	 * @param joinChild The {@link JoinableFuture} to join as a child.
	 */
	@NotNull
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final JoinRelease addDependency(@NotNull JoinableFuture<?> joinChild) {
		Requires.notNull(joinChild, "joinChild");
		if (this == joinChild) {
			// Joining oneself would be pointless.
			return JoinRelease.EMPTY;
		}

		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			List<AsyncManualResetEvent> eventsNeedNotify = null;
			synchronized (owner.getContext().getSyncContextLock()) {
				if (childOrJoinedJobs == null) {
					childOrJoinedJobs = new WeakHashMap<>(3);
				}

				Integer refCount = childOrJoinedJobs.get(joinChild);
				if (refCount == null) {
					refCount = 0;
				}

				childOrJoinedJobs.put(joinChild, ++refCount);
				if (refCount == 1) {
					// This constitutes a significant change, so we should apply synchronous task tracking to the new child.
					List<PendingNotification> tasksNeedNotify = this.addDependingSynchronousFutureToChild(joinChild);
					if (!tasksNeedNotify.isEmpty()) {
						eventsNeedNotify = new ArrayList<>(tasksNeedNotify.size());
						for (PendingNotification taskToNotify : tasksNeedNotify) {
							if (taskToNotify.getSynchronousFuture().pendingEventSource == null || taskToNotify.getFutureHasPendingMessages() == taskToNotify.getSynchronousFuture()) {
								taskToNotify.getSynchronousFuture().pendingEventSource = new WeakReference<>(taskToNotify.getFutureHasPendingMessages());
							}

							taskToNotify.getSynchronousFuture().pendingEventCount.addAndGet(taskToNotify.getNewPendingMessagesCount());

							AsyncManualResetEvent notifyEvent = taskToNotify.getSynchronousFuture().queueNeedProcessEvent;
							if (notifyEvent != null) {
								eventsNeedNotify.add(notifyEvent);
							}
						}
					}
				}
			}

			// We explicitly do this outside our lock.
			if (eventsNeedNotify != null) {
				for (AsyncManualResetEvent queueEvent : eventsNeedNotify) {
					queueEvent.pulseAll();
				}
			}

			return new JoinRelease(this, joinChild);
		}
	}

	@NotNull
	private JoinRelease ambientJobJoinsThis() {
		if (!this.isDone()) {
			JoinableFuture<?> ambientJob = owner.getContext().getAmbientFuture();
			if (ambientJob != null && ambientJob != this) {
				return ambientJob.addDependency(this);
			}
		}

		return JoinRelease.EMPTY;
	}

	/**
	 * The head of a singly linked list of records to track which future may process events of this future. This list
	 * should contain only futures which need be completed synchronously, and depends on this future.
	 */
	private DependentSynchronousFuture dependingSynchronousFutureTracking;

	/**
	 * Gets a value indicating whether the main thread is waiting for the future's completion
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final boolean hasMainThreadSynchronousFutureWaiting() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getFactory().getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (owner.getContext().getSyncContextLock()) {
				DependentSynchronousFuture existingFutureTracking = this.dependingSynchronousFutureTracking;
				while (existingFutureTracking != null) {
					if (existingFutureTracking.getSynchronousFuture().getState().contains(JoinableFutureFlag.SYNCHRONOUSLY_BLOCKING_MAIN_THREAD)) {
						return true;
					}

					existingFutureTracking = existingFutureTracking.getNext();
				}

				return false;
			}
		}
	}

	/**
	 * Get how many number of synchronous futures in our tracking list.
	 */
	private int countOfDependingSynchronousFutures() {
		int count = 0;
		DependentSynchronousFuture existingTaskTracking = this.dependingSynchronousFutureTracking;
		while (existingTaskTracking != null) {
			count++;
			existingTaskTracking = existingTaskTracking.getNext();
		}

		return count;
	}

	/**
	 * Check whether a future is being tracked in our tracking list.
	 */
	private boolean isDependingSynchronousFuture(JoinableFuture<?> syncFuture) {
		DependentSynchronousFuture existingTaskTracking = this.dependingSynchronousFutureTracking;
		while (existingTaskTracking != null) {
			if (existingTaskTracking.getSynchronousFuture() == syncFuture) {
				return true;
			}

			existingTaskTracking = existingTaskTracking.getNext();
		}

		return false;
	}

	/**
	 * Calculate the collection of events we need trigger after we enqueue a request.
	 *
	 * @param forMainThread {@code true} if we want to find futures to process the main thread queue. Otherwise futures
	 * to process the background queue.
	 * @return The collection of synchronous futures we need notify.
	 */
	private List<JoinableFuture<?>> getDependingSynchronousFutures(boolean forMainThread) {
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());

		List<JoinableFuture<?>> tasksNeedNotify = new ArrayList<>(countOfDependingSynchronousFutures());
		DependentSynchronousFuture existingTaskTracking = this.dependingSynchronousFutureTracking;
		while (existingTaskTracking != null) {
			JoinableFuture<?> syncTask = existingTaskTracking.getSynchronousFuture();
			boolean syncTaskInOnMainThread = syncTask.state.contains(JoinableFutureFlag.SYNCHRONOUSLY_BLOCKING_MAIN_THREAD);
			if (forMainThread == syncTaskInOnMainThread) {
				// Only synchronous futures are in the list, so we don't need do further check for the COMPLETING_SYNCHRONOUSLY flag
				tasksNeedNotify.add(syncTask);
			}

			existingTaskTracking = existingTaskTracking.getNext();
		}

		return tasksNeedNotify;
	}

	/**
	 * Applies all synchronous tasks tracked by this task to a new child/dependent task.
	 *
	 * @param child The new child future.
	 * @return Pairs of synchronous futures we need notify and the event source triggering it, plus the number of
	 * pending events.
	 */
	private List<PendingNotification> addDependingSynchronousFutureToChild(@NotNull JoinableFuture<?> child) {
		Requires.notNull(child, "child");
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());

		List<PendingNotification> tasksNeedNotify = new ArrayList<>(countOfDependingSynchronousFutures());
		DependentSynchronousFuture existingTaskTracking = this.dependingSynchronousFutureTracking;
		while (existingTaskTracking != null) {
			AtomicInteger totalEventNumber = new AtomicInteger(0);
			JoinableFuture<?> eventTriggeringTask = child.addDependingSynchronousFuture(existingTaskTracking.getSynchronousFuture(), totalEventNumber);
			if (eventTriggeringTask != null) {
				tasksNeedNotify.add(new PendingNotification(existingTaskTracking.getSynchronousFuture(), eventTriggeringTask, totalEventNumber.get()));
			}

			existingTaskTracking = existingTaskTracking.getNext();
		}

		return tasksNeedNotify;
	}

	/**
	 * Removes all synchronous futures we applies to a dependent future, after the relationship is removed.
	 *
	 * @param child The original dependent future
	 */
	private void removeDependingSynchronousFutureFromChild(@NotNull JoinableFuture<?> child) {
		Requires.notNull(child, "child");
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());

		DependentSynchronousFuture existingTaskTracking = this.dependingSynchronousFutureTracking;
		while (existingTaskTracking != null) {
			child.removeDependingSynchronousFuture(existingTaskTracking.getSynchronousFuture());
			existingTaskTracking = existingTaskTracking.getNext();
		}
	}

	/**
	 * Get the number of pending messages to be process for the synchronous future.
	 *
	 * @param future The synchronous future.
	 * @return The number of events need be processed by the synchronous future in the current {@link JoinableFuture}.
	 */
	private int getPendingEventCountForFuture(@NotNull JoinableFuture<?> future) {
		ExecutionQueue queue = future.state.contains(JoinableFutureFlag.SYNCHRONOUSLY_BLOCKING_MAIN_THREAD)
			? this.mainThreadQueue
			: this.threadPoolQueue;
		return queue != null ? queue.size() : 0;
	}

	/**
	 * Tracks a new synchronous future for this future. A synchronous future is a future blocking a thread and waits it
	 * to be completed. We may want the blocking thread to process events from this future.
	 *
	 * @param future The synchronous future.
	 * @param totalEventsPending The total events need be processed.
	 * @return The future causes us to trigger the event of the synchronous future, so it can process new events.
	 * {@code null} means we don't need trigger any event.
	 */
	@Nullable
	private JoinableFuture<?> addDependingSynchronousFuture(@NotNull JoinableFuture<?> future, @NotNull AtomicInteger totalEventsPending) {
		Requires.notNull(future, "future");
		Requires.notNull(totalEventsPending, "totalEventsPending");
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());

		if (isDone()) {
			return null;
		}

		if (isCompleteRequested()) {
			// A completed future might still have pending items in the queue.
			int pendingCount = this.getPendingEventCountForFuture(future);
			if (pendingCount > 0) {
				totalEventsPending.set(totalEventsPending.get() + pendingCount);
				return this;
			}

			return null;
		}

		DependentSynchronousFuture existingTaskTracking = this.dependingSynchronousFutureTracking;
		while (existingTaskTracking != null) {
			if (existingTaskTracking.getSynchronousFuture() == future) {
				existingTaskTracking.setReferenceCount(existingTaskTracking.getReferenceCount() + 1);
				return null;
			}

			existingTaskTracking = existingTaskTracking.getNext();
		}

		int pendingItemCount = getPendingEventCountForFuture(future);
		JoinableFuture<?> eventTriggeringTask = null;

		if (pendingItemCount > 0) {
			totalEventsPending.set(totalEventsPending.get() + pendingItemCount);
			eventTriggeringTask = this;
		}

		// For a new synchronous future, we need apply it to our child futures.
		DependentSynchronousFuture newTaskTracking = new DependentSynchronousFuture(future);
		newTaskTracking.setNext(dependingSynchronousFutureTracking);
		this.dependingSynchronousFutureTracking = newTaskTracking;

		if (childOrJoinedJobs != null) {
			for (JoinableFuture<?> item : childOrJoinedJobs.keySet()) {
				JoinableFuture<?> childTiggeringTask = item.addDependingSynchronousFuture(future, totalEventsPending);
				if (eventTriggeringTask == null) {
					eventTriggeringTask = childTiggeringTask;
				}
			}
		}

		return eventTriggeringTask;
	}

	/**
	 * Remove all synchronous futures tracked by the this future. This is called when this future is completed.
	 */
	private void cleanupDependingSynchronousFuture() {
		if (this.dependingSynchronousFutureTracking != null) {
			DependentSynchronousFuture existingTaskTracking = this.dependingSynchronousFutureTracking;
			this.dependingSynchronousFutureTracking = null;

			if (this.childOrJoinedJobs != null) {
				List<JoinableFuture<?>> childrenTasks = new ArrayList<>(childOrJoinedJobs.keySet());
				while (existingTaskTracking != null) {
					removeDependingSynchronousFutureFrom(childrenTasks, existingTaskTracking.getSynchronousFuture(), false);
					existingTaskTracking = existingTaskTracking.getNext();
				}
			}
		}
	}

	/**
	 * Remove a synchronous future from the tracking list.
	 *
	 * @param future The synchronous future.
	 */
	private void removeDependingSynchronousFuture(@NotNull JoinableFuture<?> future) {
		removeDependingSynchronousFuture(future, false);
	}

	/**
	 * Remove a synchronous future from the tracking list.
	 *
	 * @param future The synchronous future.
	 * @param force We always remove it from the tracking list if it is true. Otherwise, we keep tracking the reference
	 * count.
	 */
	private void removeDependingSynchronousFuture(@NotNull JoinableFuture<?> future, boolean force) {
		Requires.notNull(future, "future");
		assert Thread.holdsLock(owner.getContext().getSyncContextLock());

		if (future.dependingSynchronousFutureTracking != null) {
			removeDependingSynchronousFutureFrom(Arrays.asList(this), future, force);
		}
	}

	/**
	 * Remove a synchronous future from the tracking list of a list of futures.
	 *
	 * @param tasks A list of futures we need update the tracking list.
	 * @param syncTask The synchronous future we want to remove.
	 * @param force We always remove it from the tracking list if it is true. Otherwise, we keep tracking the reference
	 * count.
	 */
	private static void removeDependingSynchronousFutureFrom(List<? extends JoinableFuture<?>> tasks, JoinableFuture<?> syncTask, boolean force) {
		Requires.notNull(tasks, "futures");
		Requires.notNull(syncTask, "synchronousFuture");

		Set<JoinableFuture<?>> reachableTasks = null;
		final StrongBox<Set<JoinableFuture<?>>> remainTasks = new StrongBox<>();

		if (force) {
			reachableTasks = new HashSet<>();
		}

		for (JoinableFuture<?> task : tasks) {
			task.removeDependingSynchronousFuture(syncTask, reachableTasks, remainTasks);
		}

		if (!force && remainTasks.value != null && !remainTasks.value.isEmpty()) {
			// a set of tasks may form a dependent loop, so it will make the reference count system
			// not to work correctly when we try to remove the synchronous task.
			// To get rid of those loops, if a task still tracks the synchronous task after reducing
			// the reference count, we will calculate the entire reachable tree from the root.  That will
			// tell us the exactly tasks which need track the synchronous task, and we will clean up the rest.
			reachableTasks = new HashSet<>();
			syncTask.computeSelfAndDescendentOrJoinedJobsAndRemainFutures(reachableTasks, remainTasks.value);

			// force to remove all invalid items
			final StrongBox<Set<JoinableFuture<?>>> remainPlaceHold = new StrongBox<>();
			for (JoinableFuture<?> remainTask : remainTasks.value) {
				remainTask.removeDependingSynchronousFuture(syncTask, reachableTasks, remainPlaceHold);
			}
		}
	}

	/**
	 * Compute all reachable futures from a synchronous future. Because we use the result to clean up invalid items from
	 * the remain future, we will remove valid future from the collection, and stop immediately if nothing is left.
	 *
	 * @param reachableTasks All reachable futures. This is not a completed list, if there is no remain future.
	 * @param remainTasks The remain futures we want to check. After the execution, it will retain non-reachable
	 * futures.
	 */
	private void computeSelfAndDescendentOrJoinedJobsAndRemainFutures(@NotNull Set<JoinableFuture<?>> reachableTasks, @NotNull Set<JoinableFuture<?>> remainTasks) {
		Requires.notNull(remainTasks, "remainingFutures");
		Requires.notNull(reachableTasks, "reachableFutures");

		if (!isDone()) {
			if (reachableTasks.add(this)) {
				if (remainTasks.remove(this) && reachableTasks.isEmpty()) {
					// no remain future left, quit the loop earlier
					return;
				}

				if (this.childOrJoinedJobs != null) {
					for (JoinableFuture<?> item : childOrJoinedJobs.keySet()) {
						item.computeSelfAndDescendentOrJoinedJobsAndRemainFutures(reachableTasks, remainTasks);
					}
				}
			}
		}
	}

	/**
	 * Remove a synchronous future from the tracking list of this future.
	 *
	 * @param task The synchronous future need be removed
	 * @param reachableTasks If it is not {@code null}, it will contain all future which can track the synchronous
	 * future. We will ignore reference count in that case.
	 * @param remainingDependentTasks This will retain the futures which still tracks the synchronous future.
	 */
	private void removeDependingSynchronousFuture(@NotNull JoinableFuture<?> task, @Nullable Set<JoinableFuture<?>> reachableTasks, @NotNull StrongBox<Set<JoinableFuture<?>>> remainingDependentTasks) {
		Requires.notNull(task, "future");

		DependentSynchronousFuture previousTaskTracking = null;
		DependentSynchronousFuture currentTaskTracking = this.dependingSynchronousFutureTracking;
		boolean removed = false;

		while (currentTaskTracking != null) {
			if (currentTaskTracking.getSynchronousFuture() == task) {
				currentTaskTracking.setReferenceCount(currentTaskTracking.getReferenceCount() - 1);
				if (currentTaskTracking.getReferenceCount() > 0) {
					if (reachableTasks != null) {
						if (!reachableTasks.contains(this)) {
							currentTaskTracking.setReferenceCount(0);
						}
					}
				}

				if (currentTaskTracking.getReferenceCount() == 0) {
					removed = true;
					if (previousTaskTracking != null) {
						previousTaskTracking.setNext(currentTaskTracking.getNext());
					} else {
						this.dependingSynchronousFutureTracking = currentTaskTracking.getNext();
					}
				}

				if (reachableTasks == null) {
					if (removed) {
						if (remainingDependentTasks.value != null) {
							remainingDependentTasks.value.remove(this);
						}
					} else {
						if (remainingDependentTasks.value == null) {
							remainingDependentTasks.value = new HashSet<>();
						}

						remainingDependentTasks.value.add(this);
					}
				}

				break;
			}

			previousTaskTracking = currentTaskTracking;
			currentTaskTracking = currentTaskTracking.getNext();
		}

		if (removed && this.childOrJoinedJobs != null) {
			for (JoinableFuture<?> item : this.childOrJoinedJobs.keySet()) {
				item.removeDependingSynchronousFuture(task, reachableTasks, remainingDependentTasks);
			}
		}
	}

	/**
	 * The record of a pending notification we need send to the synchronous future that we have some new messages to
	 * process.
	 */
	private static final class PendingNotification {

		private final JoinableFuture<?> synchronousFuture;
		private final JoinableFuture<?> futureHasPendingMessages;
		private final int newPendingMessagesCount;

		public PendingNotification(@NotNull JoinableFuture<?> synchronousFuture, @NotNull JoinableFuture<?> futureHasPendingMessages, int newPendingMessagesCount) {
			Requires.notNull(synchronousFuture, "synchronousFuture");
			Requires.notNull(futureHasPendingMessages, "futureHasPendingMessages");

			this.synchronousFuture = synchronousFuture;
			this.futureHasPendingMessages = futureHasPendingMessages;
			this.newPendingMessagesCount = newPendingMessagesCount;
		}

		/**
		 * Gets the synchronous future which need process new messages.
		 */
		@NotNull
		public final JoinableFuture<?> getSynchronousFuture() {
			return this.synchronousFuture;
		}

		/**
		 * Gets one {@link JoinableFuture} which may have pending messages. We may have multiple new
		 * {@link JoinableFuture}s which contains pending messages. This is just one of them. It gives the synchronous
		 * future a way to start quickly without searching all messages.
		 */
		public final JoinableFuture<?> getFutureHasPendingMessages() {
			return this.futureHasPendingMessages;
		}

		/**
		 * Gets the total number of new pending messages. The real number could be less than that, but should not be
		 * more than that.
		 */
		public final int getNewPendingMessagesCount() {
			return this.newPendingMessagesCount;
		}
	}

	/**
	 * A single linked list to maintain synchronous {@link JoinableFuture} depends on the current future, which may
	 * process the queue of the current future.
	 */
	private static class DependentSynchronousFuture {

		private final JoinableFuture<?> synchronousFuture;
		private DependentSynchronousFuture next;
		private int referenceCount;

		public DependentSynchronousFuture(JoinableFuture<?> future) {
			this.synchronousFuture = future;
			this.referenceCount = 1;
		}

		/**
		 * Gets the chain of the single linked list.
		 */
		@Nullable
		public final DependentSynchronousFuture getNext() {
			return next;
		}

		/**
		 * Sets the chain of the single linked list.
		 */
		public final void setNext(DependentSynchronousFuture next) {
			this.next = next;
		}

		/**
		 * Gets the synchronous future.
		 */
		public final JoinableFuture<?> getSynchronousFuture() {
			return synchronousFuture;
		}

		/**
		 * Gets the reference count.
		 */
		public final int getReferenceCount() {
			return referenceCount;
		}

		/**
		 * Sets the reference count. We remove the item from the list, if it reaches 0.
		 */
		public final void setReferenceCount(int referenceCount) {
			this.referenceCount = referenceCount;
		}
	}

	/**
	 * A thread-safe queue of {@link SingleExecuteProtector} elements that self-scavenges elements that are executed by
	 * other means.
	 */
	static class ExecutionQueue extends AsyncQueue<SingleExecuteProtector<?>> {
		private final JoinableFuture<?> owningJob;

		ExecutionQueue(@NotNull JoinableFuture<?> owningJob) {
			Requires.notNull(owningJob, "owningJob");
			this.owningJob = owningJob;
		}

		@Override
		protected int getInitialCapacity() {
			// in non-concurrent cases, 1 is sufficient.
			return 1;
		}

		@Override
		protected void onAdded(@NotNull SingleExecuteProtector<?> value, boolean alreadyDispatched) {
			super.onAdded(value, alreadyDispatched);

			// We only need to consider scavenging our queue if this item was
			// actually added to the queue.
			if (!alreadyDispatched) {
				Requires.notNull(value, "value");
				value.addExecutingCallback(this);

				// It's possible this value has already been executed
				// (before our event wire-up was applied). So check and
				// scavenge.
				if (value.hasBeenExecuted()) {
					scavenge();
				}
			}
		}

		@Override
		protected void onPolled(@NotNull SingleExecuteProtector<?> value) {
			Requires.notNull(value, "value");

			super.onPolled(value);
			value.removeExecutingCallback(this);
		}

		@Override
		protected void onCompleted() {
			super.onCompleted();

			owningJob.onQueueCompleted();
		}

		final void onExecuting() {
			scavenge();
		}

		private void scavenge() {
			while (poll(SingleExecuteProtector::hasBeenExecuted) != null) {
			}
		}
	}

	/**
	 * A synchronization context that forwards posted messages to the ambient job.
	 */
	static class JoinableFutureSynchronizationContext extends SynchronizationContext {
		/**
		 * The owning job factory.
		 */
		private final JoinableFutureFactory jobFactory;

		/**
		 * A flag indicating whether messages posted to this instance should execute on the main thread.
		 */
		private final boolean mainThreadAffinitized;

		/**
		 * The owning job. May be {@code null} from the beginning, or cleared after task completion.
		 */
		private JoinableFuture<?> job;

		/**
		 * Constructs a new instance of the {@link JoinableFutureSynchronizationContext} class that is affinitized to
		 * the main thread.
		 *
		 * @param owner The {@link JoinableFutureFactory} that created this instance.
		 */
		JoinableFutureSynchronizationContext(@NotNull JoinableFutureFactory owner) {
			Requires.notNull(owner, "owner");

			this.jobFactory = owner;
			this.mainThreadAffinitized = true;
		}

		/**
		 * Constructs a new instance of the {@link JoinableFutureSynchronizationContext} class.
		 *
		 * @param joinableFuture The {@link JoinableFuture} that owns this instance.
		 * @param mainThreadAffinitized A value indicating whether messages posted to this instance should execute on
		 * the main thread.
		 */
		JoinableFutureSynchronizationContext(@NotNull JoinableFuture<?> joinableFuture, boolean mainThreadAffinitized) {
			Requires.notNull(joinableFuture, "joinableFuture");

			this.jobFactory = joinableFuture.getFactory();
			this.job = joinableFuture;
			this.mainThreadAffinitized = mainThreadAffinitized;
		}

		/**
		 * Gets a value indicating whether messages posted to this instance should execute on the main thread.
		 */
		final boolean isMainThreadAffinitized() {
			return mainThreadAffinitized;
		}

		/**
		 * Forwards the specified message to the job this instance belongs to if applicable; otherwise to the factory.
		 */
		@Override
		public <T> void post(Consumer<T> callback, T state) {
			if (this.job != null) {
				this.job.post(callback, state, mainThreadAffinitized);
			} else {
				this.jobFactory.post(callback, state, mainThreadAffinitized);
			}
		}

		/**
		 * Forwards a message to the ambient job and blocks on its execution.
		 */
		@Override
		public <T> void send(Consumer<T> callback, T state) {
			Requires.notNull(callback, "callback");

			// Some folks unfortunately capture the SynchronizationContext from the UI thread
			// while this one is active.  So forward it to the underlying sync context to not break those folks.
			// Ideally this method would throw because synchronously crossing threads is a bad idea.
			if (mainThreadAffinitized) {
				if (jobFactory.getContext().isOnMainThread()) {
					callback.accept(state);
				} else {
					jobFactory.getContext().getUnderlyingSynchronizationContext().send(callback, state);
				}
			} else {
//#if DESKTOP
//                    bool isThreadPoolThread = Thread.CurrentThread.IsThreadPoolThread;
//#else
//                    // On portable profile this is the best estimation we can do.
//                    bool isThreadPoolThread = !this.jobFactory.Context.IsOnMainThread;
//#endif
				boolean isThreadPoolThread = !jobFactory.getContext().isOnMainThread();
				if (isThreadPoolThread) {
					callback.accept(state);
				} else {
					CompletableFuture.runAsync(ExecutionContext.wrap(() -> callback.accept(state))).join();
				}
			}
		}

		/**
		 * Called by the joinable future when it has completed.
		 */
		final void onCompleted() {
			// Clear out our reference to the job.
			// This SynchronizationContext may have been "captured" as part of an ExecutionContext
			// and stored away someplace indefinitely, and the task we're holding may be a
			// JoinableTask<T> where T is a very expensive object or (worse) be part of the last
			// chain holding a huge object graph in memory.
			// In any case, this sync context does not need a reference to completed jobs since
			// once a job has completed, posting to it is equivalent to posting to the job's factory.
			// And clearing out this field will cause our own Post method to post to the factory
			// so behavior is equivalent.
			this.job = null;
		}
	}

}
