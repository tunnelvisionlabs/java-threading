// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFuture.ExecutionQueue;
import com.tunnelvisionlabs.util.concurrent.JoinableFuture.JoinableFutureSynchronizationContext;
import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A factory for starting asynchronous futures that can mitigate deadlocks when the futures require the main thread of
 * an application and the main thread may itself be blocking on the completion of a future.
 *
 * <p>For more complete comments please see the {@link JoinableFutureContext}.</p>
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureFactory {
	/**
	 * The {@link JoinableFutureContext} that owns this instance.
	 */
	@NotNull
	private final JoinableFutureContext owner;

	private final SynchronizationContext mainThreadJobSyncContext;

	/**
	 * The collection to add all created futures to.
	 */
	@Nullable
	private final JoinableFutureCollection jobCollection;

	/**
	 * Backing field for {@link #getHangDetectionTimeout} and {@link #setHangDetectionTimeout}.
	 */
	private Duration hangDetectionTimeout = Duration.ofSeconds(6);

	/**
	 * Constructs a new instance of the {@link JoinableFutureFactory} class.
	 *
	 * @param owner The context for the futures created by this factory.
	 */
	public JoinableFutureFactory(@NotNull JoinableFutureContext owner) {
		this(owner, null);
	}

	/**
	 * Constructs a new instance of the {@link JoinableFutureFactory} class that adds all generated jobs to the
	 * specified collection.
	 *
	 * @param collection The collection that all futures created by this factory will belong to until they complete.
	 */
	public JoinableFutureFactory(@NotNull JoinableFutureCollection collection) {
		this(Requires.notNull(collection, "collection").getContext(), collection);
	}

	/**
	 * Constructs a new instance of the {@link JoinableFutureFactory} class.
	 *
	 * @param owner The context for the futures created by this factory.
	 * @param collection The collection that all futures created by this factory will belong to until they complete.
	 */
	JoinableFutureFactory(@NotNull JoinableFutureContext owner, @Nullable JoinableFutureCollection collection) {
		Requires.notNull(owner, "owner");
		assert collection == null || collection.getContext() == owner;

		this.owner = owner;
		this.jobCollection = collection;
		this.mainThreadJobSyncContext = new JoinableFutureSynchronizationContext(this);
	}

	/**
	 * Gets the joinable future context to which this factory belongs.
	 */
	@NotNull
	public final JoinableFutureContext getContext() {
		return this.owner;
	}

	/**
	 * Gets the synchronization context to apply before executing work associated with this factory.
	 */
	@Nullable
	final SynchronizationContext getApplicableJobSyncContext() {
		return getContext().isOnMainThread() ? mainThreadJobSyncContext : null;
	}

	/**
	 * Gets the collection to which created futures belong until they complete.
	 */
	@Nullable
	final JoinableFutureCollection getCollection() {
		return this.jobCollection;
	}

	/**
	 * Gets the timeout after which no activity while synchronously blocking suggests a hang has occurred.
	 */
	@NotNull
	protected final Duration getHangDetectionTimeout() {
		return hangDetectionTimeout;
	}

	/**
	 * Sets the timeout after which no activity while synchronously blocking suggests a hang has occurred.
	 */
	protected final void setHangDetectionTimeout(@NotNull Duration value) {
		Requires.range(value.compareTo(Duration.ZERO) > 0, "value");
		hangDetectionTimeout = value;
	}

	/**
	 * Gets the underlying {@link SynchronizationContext} that controls the main thread in the host.
	 */
	protected final SynchronizationContext getUnderlyingSynchronizationContext() {
		return getContext().getUnderlyingSynchronizationContext();
	}

	/// <summary>
	/// Gets an awaitable whose continuations execute on the synchronization context that this instance was initialized with,
	/// in such a way as to mitigate both deadlocks and reentrancy.
	/// </summary>
	/// <param name="cancellationToken">
	/// A token whose cancellation will immediately schedule the continuation
	/// on a threadpool thread.
	/// </param>
	/// <returns>An awaitable.</returns>
	/// <remarks>
	/// <example>
	/// <code>
	/// private async Task SomeOperationAsync() {
	///     // on the caller's thread.
	///     await DoAsync();
	///
	///     // Now switch to a threadpool thread explicitly.
	///     await TaskScheduler.Default;
	///
	///     // Now switch to the Main thread to talk to some STA object.
	///     await this.JobContext.SwitchToMainThreadAsync();
	///     STAService.DoSomething();
	/// }
	/// </code>
	/// </example>
	/// </remarks>
	public MainThreadAwaitable switchToMainThreadAsync() {
		return switchToMainThreadAsync(CancellationToken.none());
	}

	public MainThreadAwaitable switchToMainThreadAsync(@NotNull CancellationToken cancellationToken) {
		return new MainThreadAwaitable(this, getContext().getAmbientFuture(), cancellationToken);
	}

	/**
	 * Responds to calls to {@link JoinableFutureFactory.MainThreadAwaiter#onCompleted} by scheduling a continuation to
	 * execute on the Main thread.
	 *
	 * @param callback The callback to invoke.
	 */
	@NotNull
	final SingleExecuteProtector<?> requestSwitchToMainThread(@NotNull Runnable callback) {
		Requires.notNull(callback, "callback");

		// Make sure that this thread switch request is in a job that is captured by the job collection
		// to which this switch request belongs.
		// If an ambient job already exists and belongs to the collection, that's good enough. But if
		// there is no ambient job, or the ambient job does not belong to the collection, we must create
		// a (child) job and add that to this job factory's collection so that folks joining that factory
		// can help this switch to complete.
		StrongBox<JoinableFuture<?>> ambientJob = new StrongBox<>(getContext().getAmbientFuture());
		StrongBox<SingleExecuteProtector<?>> wrapper = new StrongBox<>(null);
		if (ambientJob.value == null || (this.jobCollection != null && !this.jobCollection.contains(ambientJob.value))) {
			JoinableFuture<?> transientFuture = runAsync(
				() -> {
					ambientJob.value = getContext().getAmbientFuture();
					wrapper.value = SingleExecuteProtector.create(ambientJob.value, callback);
					ambientJob.value.post(SingleExecuteProtector.EXECUTE_ONCE, wrapper.value, true);
					return Futures.completedNull();
				},
				/*synchronouslyBlocking:*/ false,
				/*creationOptions:*/ EnumSet.noneOf(JoinableFutureCreationOption.class)/*,
				entrypointOverride: callback*/);

			if (transientFuture.getFuture().isCompletedExceptionally()) {
				// rethrow the exception.
				transientFuture.getFuture().join();
			}
		} else {
			wrapper.value = SingleExecuteProtector.create(ambientJob.value, callback);
			ambientJob.value.post(SingleExecuteProtector.EXECUTE_ONCE, wrapper.value, true);
		}

		assert wrapper.value != null;
		return wrapper.value;
	}

	/**
	 * Posts a message to the specified underlying {@link SynchronizationContext} for processing when the main thread is
	 * freely available.
	 *
	 * @param callback The callback to invoke.
	 * @param state State to pass to the callback.
	 */
	protected <T> void postToUnderlyingSynchronizationContext(@NotNull Consumer<T> callback, T state) {
		Requires.notNull(callback, "callback");
		assert this.getUnderlyingSynchronizationContext() != null;

		this.getUnderlyingSynchronizationContext().post(callback, state);
	}

	/**
	 * Raised when a joinable future has requested a transition to the main thread.
	 *
	 * <p>This event may be raised on any thread, including the main thread.</p>
	 *
	 * @param joinableFuture The future requesting the transition to the main thread.
	 */
	protected void onTransitioningToMainThread(@NotNull JoinableFuture<?> joinableFuture) {
		Requires.notNull(joinableFuture, "joinableFuture");
	}

	/**
	 * Raised whenever a joinable future has completed a transition to the main thread.
	 *
	 * <p>This event is usually raised on the main thread, but can be on another thread when {@code canceled} is
	 * {@code true}.</p>
	 *
	 * @param joinableFuture The future whose request to transition to the main thread has completed.
	 * @param canceled A value indicating whether the transition was canceled before it was fulfilled.
	 */
	protected void onTransitionedToMainThread(@NotNull JoinableFuture<?> joinableFuture, boolean canceled) {
		Requires.notNull(joinableFuture, "joinableFuture");
	}

	/**
	 * Posts a callback to the main thread via the underlying dispatcher, or to the threadpool when no dispatcher exists
	 * on the main thread.
	 */
	final void postToUnderlyingSynchronizationContextOrThreadPool(@NotNull SingleExecuteProtector<?> callback) {
		Requires.notNull(callback, "callback");

		if (this.getUnderlyingSynchronizationContext() != null) {
			this.postToUnderlyingSynchronizationContext(SingleExecuteProtector.EXECUTE_ONCE, callback);
		} else {
			ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> SingleExecuteProtector.EXECUTE_ONCE.accept(callback)));
		}
	}

	/**
	 * Synchronously blocks the calling thread for the completion of the specified future. If running on the main
	 * thread, any applicable message pump is suppressed while the thread sleeps.
	 *
	 * <p>Implementations should take care that exceptions from faulted or canceled futures not be thrown back to the
	 * caller.</p>
	 *
	 * @param task The future whose completion is being waited on.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	protected void waitSynchronously(CompletableFuture<?> future) {
		if (getContext().isOnMainThread()) {
			// Suppress any reentrancy by causing this synchronously blocking wait to not pump any messages at all.
			try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getContext().getNoMessagePumpSynchronizationContext())) {
				waitSynchronouslyCore(future);
			}
		} else {
			waitSynchronouslyCore(future);
		}
	}

	/**
	 * Synchronously blocks the calling thread for the completion of the specified future.
	 * <p>
	 * Implementations should take care that exceptions from faulted or canceled futures not be thrown back to the
	 * caller.</p>
	 *
	 * @param future The future whose completion is being waited on.
	 */
	protected void waitSynchronouslyCore(CompletableFuture<?> future) {
		Requires.notNull(future, "future");
		// useful for debugging dump files to see how many times we looped.
		int hangTimeoutsCount = 0;
		int hangNotificationCount = 0;
		UUID hangId = new UUID(0, 0);
		long stopWatch = 0;
		try {
			while (true) {
				try {
					future.get(getHangDetectionTimeout().toMillis(), TimeUnit.MILLISECONDS);
					break;
				} catch (Throwable ex) {
					// continue below
				}

				if (hangTimeoutsCount == 0) {
					stopWatch = System.nanoTime();
				}

				hangTimeoutsCount++;
				Duration hangDuration = this.getHangDetectionTimeout().multipliedBy(hangTimeoutsCount);
				if (hangId.equals(new UUID(0, 0))) {
					hangId = UUID.randomUUID();
				}

				if (!isWaitingOnLongRunningFuture()) {
					hangNotificationCount++;
					getContext().onHangDetected(hangDuration, hangNotificationCount, hangId);
				}
			}

			if (hangNotificationCount > 0) {
				// We detect a false alarm. The stop watch was started after the first timeout, so we add intial timeout to the total delay.
				this.getContext().onFalseHangDetected(
					Duration.ofNanos(System.nanoTime() - stopWatch).plus(getHangDetectionTimeout()),
					hangId);
			}
		} catch (Throwable ex) {
			// Swallow exceptions thrown by Task.Wait().
			// Our caller just wants to know when the Task completes,
			// whether successfully or not.
		}
	}

	/**
	 * Check whether the current {@link JoinableFuture} is waiting on a long running future.
	 *
	 * @return {@code true} if the current synchronous future on the thread is waiting on a long running future.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	protected final boolean isWaitingOnLongRunningFuture() {
		JoinableFuture<?> currentBlockingTask = JoinableFuture.getFutureCompletingOnThisThread();
		if (currentBlockingTask != null) {
			if (currentBlockingTask.getCreationOptions().contains(JoinableFutureCreationOption.LONG_RUNNING)) {
				return true;
			}

			try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(NoMessagePumpSyncContext.getDefault())) {
				Set<JoinableFuture<?>> allJoinedJobs = new HashSet<>();
				synchronized (getContext().getSyncContextLock()) {
					currentBlockingTask.addSelfAndDescendentOrJoinedJobs(allJoinedJobs);
					return allJoinedJobs.stream().anyMatch(t -> t.getCreationOptions().contains(JoinableFutureCreationOption.LONG_RUNNING));
				}
			}
		}

		return false;
	}

//	/// <summary>
//	/// Runs the specified asynchronous method to completion while synchronously blocking the calling thread.
//	/// </summary>
//	/// <param name="asyncMethod">The asynchronous method to execute.</param>
//	/// <remarks>
//	/// <para>Any exception thrown by the delegate is rethrown in its original type to the caller of this method.</para>
//	/// <para>When the delegate resumes from a yielding await, the default behavior is to resume in its original context
//	/// as an ordinary async method execution would. For example, if the caller was on the main thread, execution
//	/// resumes after an await on the main thread; but if it started on a threadpool thread it resumes on a threadpool thread.</para>
//	/// <example>
//	/// <code>
//	/// // On threadpool or Main thread, this method will block
//	/// // the calling thread until all async operations in the
//	/// // delegate complete.
//	/// joinableTaskFactory.Run(async delegate {
//	///     // still on the threadpool or Main thread as before.
//	///     await OperationAsync();
//	///     // still on the threadpool or Main thread as before.
//	///     await Task.Run(async delegate {
//	///          // Now we're on a threadpool thread.
//	///          await Task.Yield();
//	///          // still on a threadpool thread.
//	///     });
//	///     // Now back on the Main thread (or threadpool thread if that's where we started).
//	/// });
//	/// </code>
//	/// </example>
//	/// </remarks>
//	public <T> final T run(@NotNull Supplier<? extends CompletableFuture<T>> asyncMethod) {
//		return run(asyncMethod, EnumSet.noneOf(JoinableFutureCreationOption.class), /*entrypointOverride:*/ null);
//	}

//        /// <summary>
//        /// Runs the specified asynchronous method to completion while synchronously blocking the calling thread.
//        /// </summary>
//        /// <param name="asyncMethod">The asynchronous method to execute.</param>
//        /// <param name="creationOptions">The <see cref="JoinableTaskCreationOptions"/> used to customize the task's behavior.</param>
//        public void Run(Func<Task> asyncMethod, JoinableTaskCreationOptions creationOptions)
//        {
//            this.Run(asyncMethod, creationOptions, entrypointOverride: null);
//        }

	/**
	 * Runs the specified asynchronous method to completion while synchronously blocking the calling thread.
	 *
	 * <p>Any exception thrown by the delegate is rethrown wrapped in a {@link CompletionException} to the caller of
	 * this method.</p>
	 *
	 * <p>When the delegate resumes from a yielding await, the default behavior is to resume in its original context as
	 * an ordinary async method execution would. For example, if the caller was on the main thread, execution resumes
	 * after an await on the main thread; but if it started on a thread pool thread it resumes on a thread pool
	 * thread.</p>
	 *
	 * @param <T>
	 * @param asyncMethod
	 * @return
	 */
	public final <T> T run(Supplier<CompletableFuture<T>> asyncMethod) {
		return run(asyncMethod, EnumSet.noneOf(JoinableFutureCreationOption.class));
	}

	/**
	 * Runs the specified asynchronous method to completion while synchronously blocking the calling thread.
	 *
	 * <p>Any exception thrown by the delegate is rethrown wrapped in a {@link CompletionException} to the caller of
	 * this method.</p>
	 *
	 * <p>When the delegate resumes from a yielding await, the default behavior is to resume in its original context as
	 * an ordinary async method execution would. For example, if the caller was on the main thread, execution resumes
	 * after an await on the main thread; but if it started on a thread pool thread it resumes on a thread pool
	 * thread.</p>
	 *
	 * @param <T> The type of value returned by the asynchronous operation.
	 * @param asyncMethod The asynchronous method to execute.
	 * @param creationOptions The {@link JoinableFutureCreationOption} options used to customize the future's behavior.
	 * @return The result of the {@link CompletableFuture} returned by {@code asyncMethod}.
	 */
	public final <T> T run(Supplier<CompletableFuture<T>> asyncMethod, Set<JoinableFutureCreationOption> creationOptions) {
		verifyNoNonConcurrentSyncContext();
		JoinableFuture<T> joinable = runAsync(asyncMethod, /*synchronouslyBlocking:*/ true, /*creationOptions:*/ creationOptions);
		return joinable.completeOnCurrentThread();
	}

//        /// <summary>
//        /// Invokes an async delegate on the caller's thread, and yields back to the caller when the async method yields.
//        /// The async delegate is invoked in such a way as to mitigate deadlocks in the event that the async method
//        /// requires the main thread while the main thread is blocked waiting for the async method's completion.
//        /// </summary>
//        /// <param name="asyncMethod">The method that, when executed, will begin the async operation.</param>
//        /// <returns>An object that tracks the completion of the async operation, and allows for later synchronous blocking of the main thread for completion if necessary.</returns>
//        /// <remarks>
//        /// <para>Exceptions thrown by the delegate are captured by the returned <see cref="JoinableTask" />.</para>
//        /// <para>When the delegate resumes from a yielding await, the default behavior is to resume in its original context
//        /// as an ordinary async method execution would. For example, if the caller was on the main thread, execution
//        /// resumes after an await on the main thread; but if it started on a threadpool thread it resumes on a threadpool thread.</para>
//        /// </remarks>
//        public JoinableTask RunAsync(Func<Task> asyncMethod)
//        {
//            return this.RunAsync(asyncMethod, synchronouslyBlocking: false, creationOptions: JoinableTaskCreationOptions.None);
//        }
//
//        /// <summary>
//        /// Invokes an async delegate on the caller's thread, and yields back to the caller when the async method yields.
//        /// The async delegate is invoked in such a way as to mitigate deadlocks in the event that the async method
//        /// requires the main thread while the main thread is blocked waiting for the async method's completion.
//        /// </summary>
//        /// <param name="asyncMethod">The method that, when executed, will begin the async operation.</param>
//        /// <returns>An object that tracks the completion of the async operation, and allows for later synchronous blocking of the main thread for completion if necessary.</returns>
//        /// <param name="creationOptions">The <see cref="JoinableTaskCreationOptions"/> used to customize the task's behavior.</param>
//        /// <remarks>
//        /// <para>Exceptions thrown by the delegate are captured by the returned <see cref="JoinableTask" />.</para>
//        /// <para>When the delegate resumes from a yielding await, the default behavior is to resume in its original context
//        /// as an ordinary async method execution would. For example, if the caller was on the main thread, execution
//        /// resumes after an await on the main thread; but if it started on a threadpool thread it resumes on a threadpool thread.</para>
//        /// </remarks>
//        public JoinableTask RunAsync(Func<Task> asyncMethod, JoinableTaskCreationOptions creationOptions)
//        {
//            return this.RunAsync(asyncMethod, synchronouslyBlocking: false, creationOptions: creationOptions);
//        }
//
//        /// <summary>Runs the specified asynchronous method.</summary>
//        /// <param name="asyncMethod">The asynchronous method to execute.</param>
//        /// <param name="creationOptions">The <see cref="JoinableTaskCreationOptions"/> used to customize the task's behavior.</param>
//        /// <param name="entrypointOverride">The delegate to record as the entrypoint for this JoinableTask.</param>
//        internal void Run(Func<Task> asyncMethod, JoinableTaskCreationOptions creationOptions, Delegate entrypointOverride)
//        {
//            VerifyNoNonConcurrentSyncContext();
//            var joinable = this.RunAsync(asyncMethod, synchronouslyBlocking: true, creationOptions: creationOptions, entrypointOverride: entrypointOverride);
//            joinable.CompleteOnCurrentThread();
//        }
//
//        /// <summary>
//        /// Wraps the invocation of an async method such that it may
//        /// execute asynchronously, but may potentially be
//        /// synchronously completed (waited on) in the future.
//        /// </summary>
//        /// <param name="asyncMethod">The asynchronous method to execute.</param>
//        /// <param name="synchronouslyBlocking">A value indicating whether the launching thread will synchronously block for this job's completion.</param>
//        /// <param name="creationOptions">The <see cref="JoinableTaskCreationOptions"/> used to customize the task's behavior.</param>
//        /// <param name="entrypointOverride">The entry method's info for diagnostics.</param>
//        [SuppressMessage("Microsoft.Design", "CA1031:DoNotCatchGeneralExceptionTypes")]
//        private JoinableTask RunAsync(Func<Task> asyncMethod, bool synchronouslyBlocking, JoinableTaskCreationOptions creationOptions, Delegate entrypointOverride = null)
//        {
//            Requires.NotNull(asyncMethod, nameof(asyncMethod));
//
//            var job = new JoinableTask(this, synchronouslyBlocking, creationOptions, entrypointOverride ?? asyncMethod);
//            using (var framework = new RunFramework(this, job))
//            {
//                Task asyncMethodResult;
//                try
//                {
//                    asyncMethodResult = asyncMethod();
//                }
//                catch (Exception ex)
//                {
//                    var tcs = new TaskCompletionSource<EmptyStruct>();
//                    tcs.SetException(ex);
//                    asyncMethodResult = tcs.Task;
//                }
//
//                framework.SetResult(asyncMethodResult);
//                return job;
//            }
//        }

	/// <summary>
	/// Invokes an async delegate on the caller's thread, and yields back to the caller when the async method yields.
	/// The async delegate is invoked in such a way as to mitigate deadlocks in the event that the async method
	/// requires the main thread while the main thread is blocked waiting for the async method's completion.
	/// </summary>
	/// <typeparam name="T">The type of value returned by the asynchronous operation.</typeparam>
	/// <param name="asyncMethod">The method that, when executed, will begin the async operation.</param>
	/// <returns>
	/// An object that tracks the completion of the async operation, and allows for later synchronous blocking of the main thread for completion if necessary.
	/// </returns>
	/// <remarks>
	/// <para>Exceptions thrown by the delegate are captured by the returned <see cref="JoinableTask" />.</para>
	/// <para>When the delegate resumes from a yielding await, the default behavior is to resume in its original context
	/// as an ordinary async method execution would. For example, if the caller was on the main thread, execution
	/// resumes after an await on the main thread; but if it started on a threadpool thread it resumes on a threadpool thread.</para>
	/// </remarks>
	public final <T> JoinableFuture<T> runAsync(Supplier<? extends CompletableFuture<? extends T>> asyncMethod) {
		return runAsync(asyncMethod, /*synchronouslyBlocking:*/ false, /*creationOptions:*/ EnumSet.noneOf(JoinableFutureCreationOption.class));
	}

	/// <summary>
	/// Invokes an async delegate on the caller's thread, and yields back to the caller when the async method yields.
	/// The async delegate is invoked in such a way as to mitigate deadlocks in the event that the async method
	/// requires the main thread while the main thread is blocked waiting for the async method's completion.
	/// </summary>
	/// <typeparam name="T">The type of value returned by the asynchronous operation.</typeparam>
	/// <param name="asyncMethod">The method that, when executed, will begin the async operation.</param>
	/// <param name="creationOptions">The <see cref="JoinableTaskCreationOptions"/> used to customize the task's behavior.</param>
	/// <returns>
	/// An object that tracks the completion of the async operation, and allows for later synchronous blocking of the main thread for completion if necessary.
	/// </returns>
	/// <remarks>
	/// <para>Exceptions thrown by the delegate are captured by the returned <see cref="JoinableTask" />.</para>
	/// <para>When the delegate resumes from a yielding await, the default behavior is to resume in its original context
	/// as an ordinary async method execution would. For example, if the caller was on the main thread, execution
	/// resumes after an await on the main thread; but if it started on a threadpool thread it resumes on a threadpool thread.</para>
	/// </remarks>
	public final <T> JoinableFuture<T> runAsync(Supplier<? extends CompletableFuture<? extends T>> asyncMethod, Set<JoinableFutureCreationOption> creationOptions) {
		return runAsync(asyncMethod, /*synchronouslyBlocking:*/ false, creationOptions);
	}

	final <T> void post(@NotNull Consumer<T> callback, T state, boolean mainThreadAffinitized) {
		Requires.notNull(callback, "callback");

		if (mainThreadAffinitized) {
			JoinableFuture<?> transientFuture = this.runAsync(() -> {
				this.getContext().getAmbientFuture().post(callback, state, true);
				return Futures.completedNull();
			});

			if (transientFuture.getFuture().isCompletedExceptionally()) {
				// rethrow the exception.
				transientFuture.getFuture().join();
			}
		} else {
			ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> callback.accept(state)));
		}
	}

	/**
	 * Adds the specified joinable future to the applicable collection.
	 */
	protected final void add(@NotNull JoinableFuture<?> joinable) {
		Requires.notNull(joinable, "joinable");
		if (jobCollection != null) {
			jobCollection.add(joinable);
		}
	}

	/**
	 * Throws an exception if an active {@link AsyncReaderWriterLock} upgradeable read or write lock is held by the
	 * caller.
	 *
	 * <p>This is important to call from the {@link #run} methods because if they are called from within an
	 * {@link AsyncReaderWriterLock} upgradeable read or write lock, then {@link #run} will synchronously block while
	 * inside the semaphore held by the {@link AsyncReaderWriterLock} that prevents concurrency. If the delegate within
	 * {@link #run} yields and then tries to reacquire the {@link AsyncReaderWriterLock} lock, it will be unable to
	 * re-enter the semaphore, leading to a deadlock. Instead, callers who hold UR/W locks should never call
	 * {@link #run}, or should switch to the STA thread first in order to exit the semaphore before calling the
	 * {@link #run} method.</p>
	 */
	private static void verifyNoNonConcurrentSyncContext() {
//		// Don't use Verify.Operation here to avoid loading a string resource in success cases.
//		if (SynchronizationContext.getCurrent() instanceof AsyncReaderWriterLock.NonConcurrentSynchronizationContext) {
//			// pops a CHK assert dialog, but doesn't throw.
////			Report.fail("NotAllowedUnderURorWLock");
//			// actually throws, even in RET.
//			Verify.failOperation("NotAllowedUnderURorWLock");
//		}
	}

	private <T> JoinableFuture<T> runAsync(@NotNull Supplier<? extends CompletableFuture<? extends T>> asyncMethod, boolean synchronouslyBlocking, @NotNull Set<JoinableFutureCreationOption> creationOptions) {
		Requires.notNull(asyncMethod, "asyncMethod");

		Method entryMethod;
		try {
			entryMethod = asyncMethod.getClass().getMethod("get");
		} catch (NoSuchMethodException | SecurityException ex) {
			entryMethod = null;
		}

		JoinableFuture<T> job = new JoinableFuture<>(this, synchronouslyBlocking, creationOptions, entryMethod);
		try (RunFramework<T> framework = new RunFramework<>(this, job)) {
			CompletableFuture<? extends T> asyncMethodResult;
			try {
				asyncMethodResult = asyncMethod.get();
			} catch (Throwable ex) {
				asyncMethodResult = Futures.completedFailed(ex);
			}

			framework.setResult(asyncMethodResult);
			return job;
		}
	}

	/**
	 * An awaitable class that facilitates an asynchronous transition to the Main thread.
	 */
	public static final class MainThreadAwaitable implements Awaitable<Void> {

		private final JoinableFutureFactory jobFactory;

		private final JoinableFuture<?> job;

		private final CancellationToken cancellationToken;

		private final boolean alwaysYield;

		/**
		 * Constructs a new instance of the {@link MainThreadAwaitable} class.
		 */
		MainThreadAwaitable(@NotNull JoinableFutureFactory jobFactory, JoinableFuture<?> job, CancellationToken cancellationToken) {
			this(jobFactory, job, cancellationToken, false);
		}

		MainThreadAwaitable(@NotNull JoinableFutureFactory jobFactory, JoinableFuture<?> job, CancellationToken cancellationToken, boolean alwaysYield) {
			Requires.notNull(jobFactory, "jobFactory");

			this.jobFactory = jobFactory;
			this.job = job;
			this.cancellationToken = cancellationToken;
			this.alwaysYield = alwaysYield;
		}

		/**
		 * Gets the awaiter.
		 */
		@NotNull
		@Override
		public MainThreadAwaiter getAwaiter() {
			return new MainThreadAwaiter(this.jobFactory, this.job, this.cancellationToken, this.alwaysYield);
		}
	}

	/**
	 * An awaiter class that facilitates an asynchronous transition to the Main thread.
	 */
	public static final class MainThreadAwaiter implements Awaiter<Void> {

		private final JoinableFutureFactory jobFactory;

		private final CancellationToken cancellationToken;

		private final boolean alwaysYield;

		private final JoinableFuture<?> job;

		/// <summary>
		/// Holds the reference to the <see cref="CancellationTokenRegistration"/> struct, so that all the copies of <see cref="MainThreadAwaiter"/> will hold
		/// the same <see cref="CancellationTokenRegistration"/> object.
		/// </summary>
		/// <remarks>
		/// This must be initialized to either null or an <see cref="Nullable{T}"/> object holding no value.
		/// If this starts as an <see cref="Nullable{T}"/> object object holding no value, then it means we are interested in the cancellation,
		/// and its state would be changed following one of these 2 patterns determined by the execution order.
		/// 1. if <see cref="OnCompleted(Action)"/> finishes before <see cref="GetResult"/> is being executed on main thread,
		/// then this will hold the real registered value after <see cref="OnCompleted(Action)"/>, and <see cref="GetResult"/>
		/// will dispose that value and set a default value of <see cref="CancellationTokenRegistration"/>.
		/// 2. if <see cref="GetResult"/> is executed on main thread before <see cref="OnCompleted(Action)"/> registers the cancellation,
		/// then this will hold a default value of <see cref="CancellationTokenRegistration"/>, and <see cref="OnCompleted(Action)"/>
		/// would not touch it.
		/// </remarks>
		private final StrongBox<CancellationTokenRegistration> cancellationRegistrationPtr;

		/// <summary>
		/// Initializes a new instance of the <see cref="MainThreadAwaiter"/> struct.
		/// </summary>
		MainThreadAwaiter(JoinableFutureFactory jobFactory, JoinableFuture<?> job, CancellationToken cancellationToken, boolean alwaysYield) {
			this.jobFactory = jobFactory;
			this.job = job;
			this.cancellationToken = cancellationToken;
			this.alwaysYield = alwaysYield;

			// Don't allocate the pointer if the cancellation future can't be canceled:
			this.cancellationRegistrationPtr = cancellationToken.canBeCancelled()
				? new StrongBox<>()
				: null;
		}

		/**
		 * Gets a value indicating whether the caller is already on the Main thread.
		 */
		@Override
		public boolean isDone() {
			if (this.alwaysYield) {
				return false;
			}

			return this.jobFactory == null
				|| this.jobFactory.getContext().isOnMainThread()
				|| this.jobFactory.getContext().getUnderlyingSynchronizationContext() == null;
		}

		/**
		 * Schedules a continuation for execution on the Main thread.
		 *
		 * @param continuation The action to invoke when the operation completes.
		 */
		@Override
		public void onCompleted(@NotNull Runnable continuation) {
			assert this.jobFactory != null;

			try {
				// In the event of a cancellation request, it becomes a race as to whether the threadpool
				// or the main thread will execute the continuation first. So we must wrap the continuation
				// in a SingleExecuteProtector so that it can't be executed twice by accident.
				// Success case of the main thread.
				SingleExecuteProtector<?> wrapper = this.jobFactory.requestSwitchToMainThread(ExecutionContext.wrap(continuation));

				// Cancellation case of a threadpool thread.
				if (this.cancellationRegistrationPtr != null) {
					// Store the cancellation token registration in the struct pointer. This way,
					// if the awaiter has been copied (since it's a struct), each copy of the awaiter
					// points to the same registration. Without this we can have a memory leak.
					CancellationTokenRegistration registration = cancellationToken.register(
						SingleExecuteProtector.EXECUTE_ONCE,
						wrapper,
						/*useSynchronizationContext:*/ false);

					// Needs a lock to avoid a race condition between this method and GetResult().
					// This method is called on a background thread. After "this.jobFactory.RequestSwitchToMainThread()" returns,
					// the continuation is scheduled and GetResult() will be called whenver it is ready on main thread.
					// We have observed sometimes GetResult() was called right after "this.jobFactory.RequestSwitchToMainThread()"
					// and before "this.cancellationToken.Register()". If that happens, that means we lose the interest on the cancellation
					// and should not register the cancellation here. Without protecting that, "this.cancellationRegistrationPtr" will be leaked.
					boolean disposeThisRegistration = false;
					synchronized (this.cancellationRegistrationPtr) {
						if (this.cancellationRegistrationPtr.value == null) {
							this.cancellationRegistrationPtr.value = registration;
						} else {
							disposeThisRegistration = true;
						}
					}

					if (disposeThisRegistration) {
						registration.close();
					}
				}
			} catch (Throwable ex) {
				// This is bad. It would cause a hang without a trace as to why, since we if can't
				// schedule the continuation, stuff would just never happen.
				// Crash now, so that a Watson report would capture the original error.
//                    Environment.FailFast("Failed to schedule time on the UI thread. A continuation would never execute.", ex);
			}
		}

		/**
		 * Called on the Main thread to prepare it to execute the continuation.
		 */
		@Override
		public Void getResult() {
			assert this.jobFactory != null;

			if (!(this.jobFactory.getContext().isOnMainThread() || this.jobFactory.getContext().getUnderlyingSynchronizationContext() == null || cancellationToken.isCancellationRequested())) {
				throw new JoinableFutureContextException("SwitchToMainThreadFailedToReachExpectedThread");
			}

			// Release memory associated with the cancellation request.
			if (this.cancellationRegistrationPtr != null) {
				CancellationTokenRegistration registration = null;
				synchronized (this.cancellationRegistrationPtr) {
					if (this.cancellationRegistrationPtr.value != null) {
						registration = this.cancellationRegistrationPtr.value;
					}

					// The reason we set this is to effectively null the struct that
					// the strong box points to. Dispose does not seem to do this. If we
					// have two copies of MainThreadAwaiter pointing to the same strongbox,
					// then if one copy executes but the other does not, we could end
					// up holding onto the memory pointed to through this pointer. By
					// resetting the value here we make sure it gets cleaned.
					//
					// In addition, assigning default(CancellationTokenRegistration) to a field that
					// stores a Nullable<CancellationTokenRegistration> effectively gives it a HasValue status,
					// which will let OnCompleted know it lost the interest on the cancellation. That is an
					// important hint for OnCompleted() in order NOT to leak the cancellation registration.
					this.cancellationRegistrationPtr.value = null;
				}

				// Intentionally deferring disposal till we exit the lock to avoid executing outside code within the lock.
				if (registration != null) {
					registration.close();
				}
			}

			// Only throw a cancellation exception if we didn't end up completing what the caller asked us to do (arrive at the main thread).
			if (!this.jobFactory.getContext().isOnMainThread()) {
				cancellationToken.throwIfCancellationRequested();
			}

			// If this method is called in a continuation after an actual yield, then SingleExecuteProtector.TryExecute
			// should have already applied the appropriate SynchronizationContext to avoid deadlocks.
			// However if no yield occurred then no TryExecute would have been invoked, so to avoid deadlocks in those
			// cases, we apply the synchronization context here.
			// We don't have an opportunity to revert the sync context change, but it turns out we don't need to because
			// this method should only be called from async methods, which automatically revert any execution context
			// changes they apply (including SynchronizationContext) when they complete, thanks to the way .NET 4.5 works.
			SynchronizationContext syncContext = this.job != null ? this.job.getApplicableJobSyncContext() : this.jobFactory.getApplicableJobSyncContext();
			SpecializedSyncContext.apply(syncContext);

			return null;
		}
	}

	/**
	 * A value to construct with a C# using block in all the {@code run} method overloads to setup and teardown the
	 * boilerplate stuff.
	 */
	private static final class RunFramework<T> implements Disposable {
		private final JoinableFutureFactory factory;
		private final SpecializedSyncContext syncContextRevert;
		private final JoinableFuture<T> joinable;
		private final JoinableFuture<?> previousJoinable;

		/**
		 * Constructs a new instance of the {@link RunFramework} class and sets up the synchronization contexts for the
		 * {@link JoinableFutureFactory#run(Supplier)} family of methods.
		 */
		RunFramework(@NotNull JoinableFutureFactory factory, JoinableFuture<T> joinable) {
			Requires.notNull(factory, "factory");
			Requires.notNull(joinable, "joinable");

			this.factory = factory;
			this.joinable = joinable;
			this.factory.add(joinable);
			this.previousJoinable = this.factory.getContext().getAmbientFuture();
			this.factory.getContext().setAmbientFuture(joinable);
			this.syncContextRevert = SpecializedSyncContext.apply(joinable.getApplicableJobSyncContext());

			// Join the ambient parent job, so the parent can dequeue this job's work.
			if (this.previousJoinable != null && !this.previousJoinable.isDone()) {
				this.previousJoinable.addDependency(joinable);

				// By definition we inherit the nesting factories of our immediate nesting task.
				ListOfOftenOne<JoinableFutureFactory> nestingFactories = this.previousJoinable.getNestingFactories();

				// And we may add our immediate nesting parent's factory to the list of
				// ancestors if it isn't already in the list.
				if (this.previousJoinable.getFactory() != this.factory) {
					if (!nestingFactories.contains(previousJoinable.getFactory())) {
						nestingFactories.add(previousJoinable.getFactory());
					}
				}

				this.joinable.setNestingFactories(nestingFactories);
			}
		}

		/**
		 * Reverts the execution context to its previous state before this instance was created.
		 */
		@Override
		public void close() {
			this.syncContextRevert.close();
			this.factory.getContext().setAmbientFuture(previousJoinable);
		}

		void setResult(@NotNull CompletableFuture<? extends T> future) {
			Requires.notNull(future, "future");
			joinable.setWrappedFuture(future);
		}
	}

	/**
	 * A delegate wrapper that ensures the delegate is only invoked at most once.
	 */
//        [DebuggerDisplay("{DelegateLabel}")]
	static class SingleExecuteProtector<T> {
		/**
		 * Executes the delegate if it has not already executed.
		 */
		static final Consumer<SingleExecuteProtector<?>> EXECUTE_ONCE = SingleExecuteProtector::tryExecute;

//            /// <summary>
//            /// Executes the delegate if it has not already executed.
//            /// </summary>
//            internal static readonly WaitCallback ExecuteOnceWaitCallback = state => ((SingleExecuteProtector)state).TryExecute();

		/**
		 * The job that created this wrapper.
		 */
		private JoinableFuture<?> job;

		private boolean raiseTransitionComplete;

		/**
		 * The callback to invoke. {@code null} if it has already been invoked.
		 */
		private final AtomicReference<Consumer<T>> invokeDelegate = new AtomicReference<>();

		/**
		 * The value to pass to the callback.
		 */
		private T state;

		/**
		 * Stores execution callbacks for {@link #addExecutingCallback}.
		 */
		private final ListOfOftenOne<JoinableFuture.ExecutionQueue> executingCallbacks = new ListOfOftenOne<>();

		/**
		 * Initializes a new instance of the {@link SingleExecuteProtector} class.
		 */
		private SingleExecuteProtector(@NotNull JoinableFuture<?> job) {
			Requires.notNull(job, "job");
			this.job = job;
		}

		/**
		 * Gets a value indicating whether this instance has already executed.
		 */
		final boolean hasBeenExecuted() {
			return invokeDelegate.get() == null;
		}

//            /// <summary>
//            /// Gets a string that describes the delegate that this instance invokes.
//            /// FOR DIAGNOSTIC PURPOSES ONLY.
//            /// </summary>
//            [SuppressMessage("Microsoft.Performance", "CA1811:AvoidUncalledPrivateCode", Justification = "Used in DebuggerDisplay attributes.")]
//            internal string DelegateLabel
//            {
//                get
//                {
//                    return this.WalkAsyncReturnStackFrames().First(); // Top frame of the return callstack.
//                }
//            }

		/**
		 * Registers for a callback when this instance is executed.
		 */
		final void addExecutingCallback(@NotNull ExecutionQueue callbackReceiver) {
			if (!hasBeenExecuted()) {
				executingCallbacks.add(callbackReceiver);
			}
		}

		/**
		 * Unregisters a callback for when this instance is executed.
		 */
		final void removeExecutingCallback(@NotNull ExecutionQueue callbackReceiver) {
			executingCallbacks.remove(callbackReceiver);
		}

		/**
		 * Walk the continuation objects inside "async state machines" to generate the return call stack. FOR DIAGNOSTIC
		 * PURPOSES ONLY.
		 */
		final Iterable<String> walkAsyncReturnStackFrames() {
			// This instance might be a wrapper of another instance of "SingleExecuteProtector".
			// If that is true, we need to follow the chain to find the inner instance of "SingleExecuteProtector".
			SingleExecuteProtector<?> singleExecuteProtector = this;
			while (singleExecuteProtector.state instanceof SingleExecuteProtector) {
				singleExecuteProtector = (SingleExecuteProtector<?>)singleExecuteProtector.state;
			}

			return Collections.emptyList();
//                var invokeDelegate = singleExecuteProtector.invokeDelegate as Method;
//                var stateDelegate = singleExecuteProtector.state as Delegate;
//
//                // We are in favor of "state" when "invokeDelegate" is a static method and "state" is the actual delegate.
//                Delegate actualDelegate = (stateDelegate != null && stateDelegate.Target != null) ? stateDelegate : invokeDelegate;
//                if (actualDelegate == null)
//                {
//                    yield return "<COMPLETED>";
//                    yield break;
//                }
//
//                foreach (var frame in actualDelegate.GetAsyncReturnStackFrames())
//                {
//                    yield return frame;
//                }
		}

		final void raiseTransitioningEvents() {
			// if this method is called twice, that's the sign of a problem.
			assert !this.raiseTransitionComplete;
			this.raiseTransitionComplete = true;
			this.job.getFactory().onTransitioningToMainThread(job);
		}

		/**
		 * Constructs a new instance of the {@link SingleExecuteProtector} class.
		 *
		 * @param job The joinable future responsible for this work.
		 * @param runnable The delegate being wrapped.
		 * @return An instance of {@link SingleExecuteProtector}.
		 */
		static SingleExecuteProtector<?> create(JoinableFuture<?> job, Runnable runnable) {
			return create(job, ignored -> runnable.run(), null);
		}

		/**
		 * Initializes a new instance of the {@link SingleExecuteProtector} class that describes the specified callback.
		 *
		 * @param job The joinable future responsible for this work.
		 * @param callback The callback to invoke.
		 * @param state The state object to pass to the callback.
		 * @return An instance of {@link SingleExecuteProtector}.
		 */
		static <T> SingleExecuteProtector<T> create(@NotNull JoinableFuture<?> job, Consumer<T> callback, T state) {
			Requires.notNull(job, "job");

			// As an optimization, recognize if what we're being handed is already an instance of this type,
			// because if it is, we don't need to wrap it with yet another instance.
			if (state instanceof SingleExecuteProtector) {
				SingleExecuteProtector<?> existing = (SingleExecuteProtector<?>)state;
				if (callback == EXECUTE_ONCE && job == existing.job) {
					@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
					SingleExecuteProtector<T> result = (SingleExecuteProtector<T>)existing;
					return result;
				}
			}

			SingleExecuteProtector<T> result = new SingleExecuteProtector<>(job);
			result.invokeDelegate.set(callback);
			result.state = state;
			return result;
		}

		/**
		 * Executes the delegate if it has not already executed.
		 */
		@SuppressWarnings(Suppressions.TRY_SCOPE)
		final boolean tryExecute() {
			Consumer<T> invokeDelegate = this.invokeDelegate.getAndSet(null);
			if (invokeDelegate != null) {
				this.onExecuting();
				SynchronizationContext syncContext = this.job != null ? this.job.getApplicableJobSyncContext() : this.job.getFactory().getApplicableJobSyncContext();
				try (SpecializedSyncContext syncContext1 = SpecializedSyncContext.apply(syncContext, /*checkForChangesOnRevert:*/ false)) {
					invokeDelegate.accept(this.state);

					// Release the rest of the memory we're referencing.
					this.state = null;
					this.job = null;
				}

				return true;
			} else {
				return false;
			}
		}

		/**
		 * Invokes {@link JoinableFuture.ExecutionQueue#onExecuting} handler.
		 */
		private void onExecuting() {
//                if (ThreadingEventSource.Instance.IsEnabled())
//                {
//                    ThreadingEventSource.Instance.PostExecutionStop(this.GetHashCode());
//                }

			// While raising the event, automatically remove the handlers since we'll only
			// raise them once, and we'd like to avoid holding references that may extend
			// the lifetime of our recipients.
			Iterator<JoinableFuture.ExecutionQueue> enumerator = this.executingCallbacks.iterateAndClear();
			while (enumerator.hasNext()) {
				enumerator.next().onExecuting();
			}

			if (this.raiseTransitionComplete) {
				this.job.getFactory().onTransitionedToMainThread(this.job, !this.job.getFactory().getContext().isOnMainThread());
			}
		}
	}

}
