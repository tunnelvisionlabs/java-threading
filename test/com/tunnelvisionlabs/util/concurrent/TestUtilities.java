// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.Assume;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
enum TestUtilities {
	;

//        /// <summary>
//        /// A value indicating whether the library is operating in .NET 4.5 mode.
//        /// </summary>
//#if DESKTOP
//        internal static readonly bool IsNet45Mode = ConfigurationManager.AppSettings["Microsoft.VisualStudio.Threading.NET45Mode"] == "true";
//#else
//        internal static readonly bool IsNet45Mode = false;
//#endif
//
//        internal static Task SetAsync(this TaskCompletionSource<object> tcs)
//        {
//            return Task.Run(() => tcs.TrySetResult(null));
//        }
//
//        /// <summary>
//        /// Runs an asynchronous task synchronously, using just the current thread to execute continuations.
//        /// </summary>
//        internal static void Run(Func<Task> func)
//        {
//            if (func == null)
//            {
//                throw new ArgumentNullException(nameof(func));
//            }
//
//            var prevCtx = SynchronizationContext.Current;
//            try
//            {
//                var syncCtx = SingleThreadedSynchronizationContext.New();
//                SynchronizationContext.SetSynchronizationContext(syncCtx);
//
//                var t = func();
//                if (t == null)
//                {
//                    throw new InvalidOperationException();
//                }
//
//                var frame = SingleThreadedSynchronizationContext.NewFrame();
//                t.ContinueWith(_ => { frame.Continue = false; }, TaskScheduler.Default);
//                SingleThreadedSynchronizationContext.PushFrame(syncCtx, frame);
//
//                t.GetAwaiter().GetResult();
//            }
//            finally
//            {
//                SynchronizationContext.SetSynchronizationContext(prevCtx);
//            }
//        }

	/**
	 * Executes the specified function on multiple threads simultaneously.
	 * @param <T> The type of the value returned by the specified function.
	 * @param action The function to invoke concurrently.
	 * @param concurrency The level of concurrency.
	 */
	static <T> List<T> concurrencyTest(@NotNull Supplier<? extends T> action) {
		return concurrencyTest(action, -1);
	}

	/**
	 * Executes the specified function on multiple threads simultaneously.
	 *
	 * @param <T> The type of the value returned by the specified function.
	 * @param action The function to invoke concurrently.
	 * @param concurrency The level of concurrency.
	 */
	static <T> List<T> concurrencyTest(@NotNull Supplier<? extends T> action, int concurrency) {
		Requires.notNull(action, "action");
		if (concurrency == -1) {
			concurrency = Math.min(Runtime.getRuntime().availableProcessors(), ForkJoinPool.getCommonPoolParallelism());
		}

		Assume.assumeTrue("The test machine does not have enough CPU cores to exercise a concurrency level of " + concurrency, Runtime.getRuntime().availableProcessors() >= concurrency);
		Assume.assumeTrue("The test machine only has " + ForkJoinPool.getCommonPoolParallelism() + " threads in the fork join pool", ForkJoinPool.getCommonPoolParallelism() >= concurrency);
		Assume.assumeTrue("Expected multiple threads for a concurrency test", concurrency > 1);

		// We use a barrier to guarantee that all threads are fully ready to
		// execute the provided function at precisely the same time.
		// The barrier will unblock all of them together.
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
			List<CompletableFuture<T>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrency; i++) {
				tasks.add(CompletableFuture.<CompletableFuture<T>>supplyAsync(() -> {
					try {
						barrier.await();
						return CompletableFuture.completedFuture(action.get());
					} catch (InterruptedException ex) {
						return Futures.completedCancelled();
					} catch (BrokenBarrierException ex) {
						return Futures.completedFailed(ex);
					}
				}, ThreadPool.commonPool()).thenCompose(AsyncFunctions.unwrap()));
			}

			CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[concurrency])).join();
			return tasks.stream().map(f -> f.join()).collect(Collectors.toList());
	}

//        internal static DebugAssertionRevert DisableAssertionDialog()
//        {
//            var listener = Debug.Listeners.OfType<DefaultTraceListener>().FirstOrDefault();
//            if (listener != null)
//            {
//                listener.AssertUiEnabled = false;
//            }
//
//            return default(DebugAssertionRevert);
//        }
//
//        internal static void CompleteSynchronously(this JoinableTaskFactory factory, JoinableTaskCollection collection, Task task)
//        {
//            Requires.NotNull(factory, nameof(factory));
//            Requires.NotNull(collection, nameof(collection));
//            Requires.NotNull(task, nameof(task));
//
//            factory.Run(async delegate
//            {
//                using (collection.Join())
//                {
//                    await task;
//                }
//            });
//        }
//
//        /// <summary>
//        /// Forces an awaitable to yield, setting signals after the continuation has been pended and when the continuation has begun execution.
//        /// </summary>
//        /// <param name="baseAwaiter">The awaiter to extend.</param>
//        /// <param name="yieldingSignal">The signal to set after the continuation has been pended.</param>
//        /// <param name="resumingSignal">The signal to set when the continuation has been invoked.</param>
//        /// <returns>A new awaitable.</returns>
//        internal static YieldAndNotifyAwaitable YieldAndNotify(this INotifyCompletion baseAwaiter, AsyncManualResetEvent yieldingSignal = null, AsyncManualResetEvent resumingSignal = null)
//        {
//            Requires.NotNull(baseAwaiter, nameof(baseAwaiter));
//
//            return new YieldAndNotifyAwaitable(baseAwaiter, yieldingSignal, resumingSignal);
//        }
//
//        /// <summary>
//        /// Flood the threadpool with requests that will just block the threads
//        /// until the returned value is disposed of.
//        /// </summary>
//        /// <returns>A value to dispose of to unblock the threadpool.</returns>
//        /// <remarks>
//        /// This can provide a unique technique for influencing execution order
//        /// of synchronous code vs. async code.
//        /// </remarks>
//        internal static IDisposable StarveThreadpool()
//        {
//            var evt = new ManualResetEventSlim();
//
//            // Flood the threadpool with work items that will just block
//            // any threads assigned to them.
//            int workerThreads, completionPortThreads;
//            ThreadPool.GetMinThreads(out workerThreads, out completionPortThreads);
//            for (int i = 0; i < workerThreads * 10; i++)
//            {
//                ThreadPool.QueueUserWorkItem(
//                    s => ((ManualResetEventSlim)s).Wait(),
//                    evt);
//            }
//
//            return new ThreadpoolStarvation(evt);
//        }
//
//        internal struct YieldAndNotifyAwaitable
//        {
//            private readonly INotifyCompletion baseAwaiter;
//            private readonly AsyncManualResetEvent yieldingSignal;
//            private readonly AsyncManualResetEvent resumingSignal;
//
//            internal YieldAndNotifyAwaitable(INotifyCompletion baseAwaiter, AsyncManualResetEvent yieldingSignal, AsyncManualResetEvent resumingSignal)
//            {
//                Requires.NotNull(baseAwaiter, nameof(baseAwaiter));
//
//                this.baseAwaiter = baseAwaiter;
//                this.yieldingSignal = yieldingSignal;
//                this.resumingSignal = resumingSignal;
//            }
//
//            public YieldAndNotifyAwaiter GetAwaiter()
//            {
//                return new YieldAndNotifyAwaiter(this.baseAwaiter, this.yieldingSignal, this.resumingSignal);
//            }
//        }
//
//        internal struct YieldAndNotifyAwaiter : INotifyCompletion
//        {
//            private readonly INotifyCompletion baseAwaiter;
//            private readonly AsyncManualResetEvent yieldingSignal;
//            private readonly AsyncManualResetEvent resumingSignal;
//
//            internal YieldAndNotifyAwaiter(INotifyCompletion baseAwaiter, AsyncManualResetEvent yieldingSignal, AsyncManualResetEvent resumingSignal)
//            {
//                Requires.NotNull(baseAwaiter, nameof(baseAwaiter));
//
//                this.baseAwaiter = baseAwaiter;
//                this.yieldingSignal = yieldingSignal;
//                this.resumingSignal = resumingSignal;
//            }
//
//            public bool IsCompleted
//            {
//                get { return false; }
//            }
//
//            public void OnCompleted(Action continuation)
//            {
//                var that = this;
//                this.baseAwaiter.OnCompleted(delegate
//                {
//                    if (that.resumingSignal != null)
//                    {
//                        that.resumingSignal.Set();
//                    }
//
//                    continuation();
//                });
//                if (this.yieldingSignal != null)
//                {
//                    this.yieldingSignal.Set();
//                }
//            }
//
//            public void GetResult()
//            {
//            }
//        }
//
//        internal struct DebugAssertionRevert : IDisposable
//        {
//            public void Dispose()
//            {
//                var listener = Debug.Listeners.OfType<DefaultTraceListener>().FirstOrDefault();
//                if (listener != null)
//                {
//                    listener.AssertUiEnabled = true;
//                }
//            }
//        }
//
//        private class ThreadpoolStarvation : IDisposable
//        {
//            private readonly ManualResetEventSlim releaser;
//
//            internal ThreadpoolStarvation(ManualResetEventSlim releaser)
//            {
//                Requires.NotNull(releaser, nameof(releaser));
//                this.releaser = releaser;
//            }
//
//            public void Dispose()
//            {
//                this.releaser.Set();
//            }
//        }
}
