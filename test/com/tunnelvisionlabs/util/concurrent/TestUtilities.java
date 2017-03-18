// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
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
				tasks.add(Futures.supplyAsync(() -> {
					try {
						barrier.await();
						return CompletableFuture.completedFuture(action.get());
					} catch (InterruptedException ex) {
						return Futures.completedCancelled();
					} catch (BrokenBarrierException ex) {
						return Futures.completedFailed(ex);
					}
				}));
			}

			CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[concurrency])).join();
			return tasks.stream().map(CompletableFuture::join).collect(Collectors.toList());
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

	static void completeSynchronously(@NotNull JoinableFutureFactory factory, @NotNull JoinableFutureCollection collection, CompletableFuture<?> future) {
		Requires.notNull(factory, "factory");
		Requires.notNull(collection, "collection");
		Requires.notNull(future, "future");

		factory.run(() -> {
			return Async.usingAsync(
				collection.join(),
				() -> Async.awaitAsync(future));
		});
	}

	/**
	 * Forces an awaitable to yield, setting signals after the continuation has been pended and when the continuation
	 * has begun execution.
	 *
	 * @param baseAwaiter The awaiter to extend.
	 * @param yieldingSignal The signal to set after the continuation has been pended.
	 * @param resumingSignal The signal to set when the continuation has been invoked.
	 * @return A new awaitable.
	 */
	static YieldAndNotifyAwaitable yieldAndNotify(@NotNull Awaiter<?> baseAwaiter, @Nullable AsyncManualResetEvent yieldingSignal, @Nullable AsyncManualResetEvent resumingSignal) {
		Requires.notNull(baseAwaiter, "baseAwaiter");

		return new YieldAndNotifyAwaitable(baseAwaiter, yieldingSignal, resumingSignal);
	}

	/**
	 * Flood the {@link ForkJoinPool#commonPool()} with requests that will just block the threads until the returned
	 * value is disposed of.
	 *
	 * <p>
	 * This can provide a unique technique for influencing execution order of synchronous code vs. async code.</p>
	 *
	 * @return A value to close to unblock the fork join pool.
	 */
	static Disposable starveForkJoinPool() {
		CompletableFuture<Void> evt = new CompletableFuture<>();

		// Flood the fork join pool with work items that will just block
		// any threads assigned to them.
		int workerThreads = ForkJoinPool.getCommonPoolParallelism();
		for (int i = 0; i < workerThreads * 10; i++) {
			ForkJoinPool.commonPool().submit(evt::join);
		}

		return new ForkJoinPoolStarvation(evt);
	}

	static final class YieldAndNotifyAwaitable implements Awaitable<Void> {

		private final Awaiter<?> baseAwaiter;
		private final AsyncManualResetEvent yieldingSignal;
		private final AsyncManualResetEvent resumingSignal;

		YieldAndNotifyAwaitable(Awaiter<?> baseAwaiter, AsyncManualResetEvent yieldingSignal, AsyncManualResetEvent resumingSignal) {
			Requires.notNull(baseAwaiter, "baseAwaiter");

			this.baseAwaiter = baseAwaiter;
			this.yieldingSignal = yieldingSignal;
			this.resumingSignal = resumingSignal;
		}

		@Override
		public YieldAndNotifyAwaiter getAwaiter() {
			return new YieldAndNotifyAwaiter(baseAwaiter, yieldingSignal, resumingSignal);
		}
	}

	static final class YieldAndNotifyAwaiter implements Awaiter<Void> {

		private final Awaiter<?> baseAwaiter;
		private final AsyncManualResetEvent yieldingSignal;
		private final AsyncManualResetEvent resumingSignal;

		YieldAndNotifyAwaiter(Awaiter<?> baseAwaiter, AsyncManualResetEvent yieldingSignal, AsyncManualResetEvent resumingSignal) {
			Requires.notNull(baseAwaiter, "baseAwaiter");

			this.baseAwaiter = baseAwaiter;
			this.yieldingSignal = yieldingSignal;
			this.resumingSignal = resumingSignal;
		}

		@Override
		public boolean isDone() {
			return false;
		}

		@Override
		public void onCompleted(Runnable continuation) {
			baseAwaiter.onCompleted(() -> {
				if (resumingSignal != null) {
					resumingSignal.set();
				}

				continuation.run();
			});
			if (this.yieldingSignal != null) {
				this.yieldingSignal.set();
			}
		}

		@Override
		public Void getResult() {
			return null;
		}
	}

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

	private static class ForkJoinPoolStarvation implements Disposable {

		private final CompletableFuture<Void> releaser;

		ForkJoinPoolStarvation(@NotNull CompletableFuture<Void> releaser) {
			Requires.notNull(releaser, "releaser");
			this.releaser = releaser;
		}

		@Override
		public void close() {
			this.releaser.complete(null);
		}
	}
}
