// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Extension methods and awaitables.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public enum AwaitExtensions {
	;

	/**
	 * Gets an awaiter that schedules continuations on the specified executor.
	 *
	 * @param executor The executor used to execute continuations.
	 * @return An awaiter.
	 */
	@NotNull
	public static ExecutorAwaiter getAwaiter(@NotNull Executor executor) {
		Requires.notNull(executor, "executor");
		return new ExecutorAwaiter(executor, false);
	}

	/**
	 * Gets an awaitable that schedules continuations on the specified executor.
	 *
	 * @param scheduler The executor used to execute continuations.
	 * @return An awaitable.
	 */
	@NotNull
	public static ExecutorAwaitable switchTo(@NotNull Executor executor) {
		return switchTo(executor, false);
	}

	/**
	 * Gets an awaitable that schedules continuations on the specified executor.
	 *
	 * @param scheduler The executor used to execute continuations.
	 * @param alwaysYield A value indicating whether the caller should yield even if already executing on the desired
	 * executor.
	 * @return An awaitable.
	 */
	@NotNull
	public static ExecutorAwaitable switchTo(@NotNull Executor executor, boolean alwaysYield) {
		Requires.notNull(executor, "executor");
		return new ExecutorAwaitable(executor, alwaysYield);
	}

	/**
	 * An awaitable that executes continuations on the specified executor.
	 */
	public static final class ExecutorAwaitable implements Awaitable<Void> {

		/**
		 * The executor for continuations.
		 */
		private final Executor executor;

		/**
		 * A value indicating whether the awaitable will always call the caller to yield.
		 */
		private final boolean alwaysYield;

		/**
		 * Constructs a new instance of the {@link ExecutorAwaitable} class.
		 *
		 * @param executor The executor used to execute continuations.
		 * @param alwaysYield A value indicating whether the caller should yield even if already executing on the
		 * desired executor.
		 */
		public ExecutorAwaitable(@NotNull Executor executor, boolean alwaysYield) {
			Requires.notNull(executor, "executor");

			this.executor = executor;
			this.alwaysYield = alwaysYield;
		}

		/**
		 * Gets an awaiter that schedules continuations on the specified scheduler.
		 *
		 * @return
		 */
		@NotNull
		@Override
		public ExecutorAwaiter getAwaiter() {
			return new ExecutorAwaiter(this.executor, this.alwaysYield);
		}
	}

	/**
	 * An awaiter returned from {@link #getAwaiter(Executor)}.
	 */
	public static final class ExecutorAwaiter implements Awaiter<Void>, CriticalNotifyCompletion {

		/**
		 * The executor for continuations.
		 */
		private final Executor executor;

		/**
		 * A value indicating whether {@link #isDone()} should always return {@code false}.
		 */
		private final boolean alwaysYield;

		/**
		 * Constructs a new instance of the {@link ExecutorAwaiter} class.
		 *
		 * @param executor The executor for continuations.
		 * @param alwaysYield A value indicating whether the caller should yield even if already executing on the
		 * desired executor.
		 */
		public ExecutorAwaiter(Executor executor, boolean alwaysYield) {
			this.executor = executor;
			this.alwaysYield = alwaysYield;
		}

		/**
		 * Gets a value indicating whether no yield is necessary.
		 *
		 * @return {@code true} if the caller is already running on that {@link Executor}.
		 */
		@Override
		public boolean isDone() {
			if (this.alwaysYield) {
				return false;
			}

			if (executor instanceof ForkJoinPool) {
				Thread currentThread = Thread.currentThread();
				if (currentThread instanceof ForkJoinWorkerThread) {
					ForkJoinWorkerThread forkJoinWorkerThread = (ForkJoinWorkerThread)currentThread;
					return forkJoinWorkerThread.getPool() == executor;
				}
			}

			return false;
//                    // We special case the TaskScheduler.Default since that is semantically equivalent to being
//                    // on a ThreadPool thread, and there are various ways to get on those threads.
//                    // TaskScheduler.Current is never null.  Even if no scheduler is really active and the current
//                    // thread is not a threadpool thread, TaskScheduler.Current == TaskScheduler.Default, so we have
//                    // to protect against that case too.
//#if DESKTOP
//                    bool isThreadPoolThread = Thread.CurrentThread.IsThreadPoolThread;
//#else
//                    // An approximation of whether we're on a threadpool thread is whether
//                    // there is a SynchronizationContext applied. So use that, since it's
//                    // available to portable libraries.
//                    bool isThreadPoolThread = SynchronizationContext.Current == null;
//#endif
//                    return (this.scheduler == TaskScheduler.Default && isThreadPoolThread)
//                        || (this.scheduler == TaskScheduler.Current && TaskScheduler.Current != TaskScheduler.Default);
		}

		/**
		 * Schedules a continuation to execute using the specified executor.
		 *
		 * @param continuation The continuation to execute.
		 */
		@Override
		public void onCompleted(@NotNull Runnable continuation) {
			executor.execute(ExecutionContext.wrap(continuation));
		}

		@Override
		public void unsafeOnCompleted(@NotNull Runnable continuation) {
			executor.execute(continuation);
		}

		/**
		 * Does nothing.
		 */
		@Override
		public Void getResult() {
			return null;
		}
	}
}
