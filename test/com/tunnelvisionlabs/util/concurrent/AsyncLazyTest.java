// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.SingleThreadedSynchronizationContext.Frame;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.isA;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncLazyTest extends TestBase {

	@Test
	public void testBasic() {
		GenericParameterHelper expected = new GenericParameterHelper(5);
		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(
			() -> Async.awaitAsync(
				Async.yieldAsync(),
				() -> CompletableFuture.completedFuture(expected)));

		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			lazy.getValueAsync(),
			actual -> {
				Assert.assertSame(expected, actual);
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testIsValueCreated() {
		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(
			() -> Async.awaitAsync(
				// Wait here, so we can verify that isValueCreated is true
				// before the value factory has completed execution.
				evt.waitAsync(),
				() -> CompletableFuture.completedFuture(new GenericParameterHelper(5))));

		Assert.assertFalse(lazy.isValueCreated());
		CompletableFuture<? extends GenericParameterHelper> resultTask = lazy.getValueAsync();
		Assert.assertTrue(lazy.isValueCreated());
		evt.set();
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			resultTask,
			data -> {
				Assert.assertEquals(5, data.getData());
				Assert.assertTrue(lazy.isValueCreated());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testIsValueFactoryCompleted() {
		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(
			() -> Async.awaitAsync(
				evt.waitAsync(),
				() -> CompletableFuture.completedFuture(new GenericParameterHelper(5))));

		Assert.assertFalse(lazy.isValueFactoryCompleted());
		CompletableFuture<? extends GenericParameterHelper> resultTask = lazy.getValueAsync();
		Assert.assertFalse(lazy.isValueFactoryCompleted());
		evt.set();
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			resultTask,
			data -> {
				Assert.assertEquals(5, data.getData());
				Assert.assertTrue(lazy.isValueFactoryCompleted());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testCtorNullArgs() {
		thrown.expect(NullPointerException.class);
		AsyncLazy<Object> asyncLazy = new AsyncLazy<>(null);
	}

	@Test
	public void testValueFactoryExecutedOnlyOnceSequential_SpecifyJff() {
		testValueFactoryExecutedOnlyOnceSequential(true);
	}

	@Test
	public void testValueFactoryExecutedOnlyOnceSequential_NoSpecifyJff() {
		testValueFactoryExecutedOnlyOnceSequential(false);
	}

	/**
	 * Verifies that multiple sequential calls to {@link AsyncLazy#getValueAsync} do not result in multiple invocations
	 * of the value factory.
	 */
	private void testValueFactoryExecutedOnlyOnceSequential(boolean specifyJtf) {
		// use our own so we don't get main thread deadlocks, which isn't the point of this test.
		JoinableFutureFactory jtf = specifyJtf ? new JoinableFutureContext().getFactory() : null;
		AtomicBoolean valueFactoryExecuted = new AtomicBoolean(false);
		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(
			() -> {
				Assert.assertFalse(valueFactoryExecuted.get());
				valueFactoryExecuted.set(true);
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> CompletableFuture.completedFuture(new GenericParameterHelper(5)));
			},
			jtf);

		CompletableFuture<? extends GenericParameterHelper> task1 = lazy.getValueAsync();
		CompletableFuture<? extends GenericParameterHelper> task2 = lazy.getValueAsync();
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			task1,
			actual1 -> Async.awaitAsync(
				task2,
				actual2 -> {
					Assert.assertSame(actual1, actual2);
					Assert.assertEquals(5, actual1.getData());
					return Futures.completedNull();
				}));
	}

	@Test
	public void testValueFactoryExecutedOnlyOnceConcurrent_SpecifyJff() {
		testValueFactoryExecutedOnlyOnceConcurrent(true);
	}

	@Test
	public void testValueFactoryExecutedOnlyOnceConcurrent_NoSpecifyJff() {
		testValueFactoryExecutedOnlyOnceConcurrent(false);
	}

	/**
	 * Verifies that multiple concurrent calls to {@link AsyncLazy#getValueAsync()} do not result in multiple
	 * invocations of the value factory.
	 */
	private void testValueFactoryExecutedOnlyOnceConcurrent(boolean specifyJtf) {
		// use our own so we don't get main thread deadlocks, which isn't the point of this test.
		JoinableFutureFactory jtf = specifyJtf ? new JoinableFutureContext().getFactory() : null;
		CompletableFuture<Void> cts = Async.delayAsync(ASYNC_DELAY);
		while (!cts.isDone()) {
			// for debugging purposes only
			AtomicBoolean valueFactoryResumed = new AtomicBoolean(false);
			AtomicBoolean valueFactoryExecuted = new AtomicBoolean(false);
			AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(
				() -> {
					Assert.assertFalse(valueFactoryExecuted.get());
					valueFactoryExecuted.set(true);
					return Async.awaitAsync(
						Async.yieldAsync(),
						() -> {
							valueFactoryResumed.set(true);
							return CompletableFuture.completedFuture(new GenericParameterHelper(5));
						});
				},
				jtf);

			List<GenericParameterHelper> results = TestUtilities.<GenericParameterHelper>concurrencyTest(() -> lazy.getValueAsync().join());

			Assert.assertEquals(5, results.get(0).getData());
			for (int i = 1; i < results.size(); i++) {
				Assert.assertSame(results.get(0), results.get(i));
			}
		}
	}

//        [Fact]
//        public async Task ValueFactoryReleasedAfterExecution()
//        {
//            for (int i = 0; i < 10; i++)
//            {
//                this.Logger.WriteLine("Iteration {0}", i);
//                WeakReference collectible = null;
//                AsyncLazy<object> lazy = null;
//                ((Action)(() =>
//                {
//                    var closure = new { value = new object() };
//                    collectible = new WeakReference(closure);
//                    lazy = new AsyncLazy<object>(async delegate
//                    {
//                        await Task.Yield();
//                        return closure.value;
//                    });
//                }))();
//
//                Assert.True(collectible.IsAlive);
//                var result = await lazy.GetValueAsync();
//
//                for (int j = 0; j < 3 && collectible.IsAlive; j++)
//                {
//                    GC.Collect(2, GCCollectionMode.Forced, true);
//                    await Task.Yield();
//                }
//
//                // It turns out that the GC isn't predictable.  But as long as
//                // we can get an iteration where the value has been GC'd, we can
//                // be confident that the product is releasing the reference.
//                if (!collectible.IsAlive)
//                {
//                    return; // PASS.
//                }
//            }
//
//            Assert.True(false, "The reference was never released");
//        }
//
//        [Theory, CombinatorialData]
//        [Trait("TestCategory", "FailsInCloudTest")]
//        public async Task AsyncPumpReleasedAfterExecution(bool throwInValueFactory)
//        {
//            WeakReference collectible = null;
//            AsyncLazy<object> lazy = null;
//            ((Action)(() =>
//            {
//                var context = new JoinableTaskContext(); // we need our own collectible context.
//                collectible = new WeakReference(context.Factory);
//                var valueFactory = throwInValueFactory
//                    ? new Func<Task<object>>(delegate { throw new ApplicationException(); })
//                    : async delegate
//                    {
//                        await Task.Yield();
//                        return new object();
//                    };
//                lazy = new AsyncLazy<object>(valueFactory, context.Factory);
//            }))();
//
//            Assert.True(collectible.IsAlive);
//            await lazy.GetValueAsync().NoThrowAwaitable();
//
//            var cts = new CancellationTokenSource(AsyncDelay);
//            while (!cts.IsCancellationRequested && collectible.IsAlive)
//            {
//                await Task.Yield();
//                GC.Collect();
//            }
//
//            Assert.False(collectible.IsAlive);
//        }

	@Test
	public void testValueFactoryThrowsSynchronously_SpecifyJff() {
		testValueFactoryThrowsSynchronously(true);
	}

	@Test
	public void testValueFactoryThrowsSynchronously_NoSpecifyJff() {
		testValueFactoryThrowsSynchronously(false);
	}

//        [Theory, CombinatorialData]
	public void testValueFactoryThrowsSynchronously(boolean specifyJff) {
		// use our own so we don't get main thread deadlocks, which isn't the point of this test.
		JoinableFutureFactory jff = specifyJff ? new JoinableFutureContext().getFactory() : null;
		AtomicBoolean executed = new AtomicBoolean(false);
		AsyncLazy<Object> lazy = new AsyncLazy<>(
			() -> {
				Assert.assertFalse(executed.get());
				executed.set(true);
				throw new RuntimeException();
			},
			jff);

		CompletableFuture<? extends Object> future1 = lazy.getValueAsync();
		CompletableFuture<? extends Object> future2 = lazy.getValueAsync();
		Assert.assertNotSame("Futures must be different to isolate cancellation.", future1, future2);
		Assert.assertTrue(future1.isDone());
		Assert.assertTrue(future1.isCompletedExceptionally());
		Assert.assertFalse(future1.isCancelled());

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(RuntimeException.class));
		future1.join();
	}

	@Test
	public void testValueFactoryReentersValueFactorySynchronously_SpecifyJff() {
		testValueFactoryReentersValueFactorySynchronously(true);
	}

	@Test
	public void testValueFactoryReentersValueFactorySynchronously_NoSpecifyJff() {
		testValueFactoryReentersValueFactorySynchronously(false);
	}

//        [Theory, CombinatorialData]
	private void testValueFactoryReentersValueFactorySynchronously(boolean specifyJtf) {
		// use our own so we don't get main thread deadlocks, which isn't the point of this test.
		JoinableFutureFactory jtf = specifyJtf ? new JoinableFutureContext().getFactory() : null;
		AtomicReference<AsyncLazy<Object>> lazy = new AtomicReference<>();
		AtomicBoolean executed = new AtomicBoolean(false);
		lazy.set(new AsyncLazy<>(
			() -> {
				Assert.assertFalse(executed.get());
				executed.set(true);
				lazy.get().getValueAsync();
				return CompletableFuture.completedFuture(new Object());
			},
			jtf));

		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			AsyncAssert.assertThrowsAsync(IllegalStateException.class, () -> lazy.get().getValueAsync()),
			() -> Async.awaitAsync(
				// Do it again, to verify that AsyncLazy recorded the failure and will replay it.
				AsyncAssert.assertThrowsAsync(IllegalStateException.class, () -> lazy.get().getValueAsync())));

		asyncTest.join();
	}

	@Test
	public void testValueFactoryReentersValueFactoryAsynchronously_SpecifyJff() {
		testValueFactoryReentersValueFactoryAsynchronously(true);
	}

	@Test
	public void testValueFactoryReentersValueFactoryAsynchronously_NoSpecifyJff() {
		testValueFactoryReentersValueFactoryAsynchronously(false);
	}

//        [Theory, CombinatorialData]
	private void testValueFactoryReentersValueFactoryAsynchronously(boolean specifyJff) {
		// use our own so we don't get main thread deadlocks, which isn't the point of this test.
		JoinableFutureFactory jtf = specifyJff ? new JoinableFutureContext().getFactory() : null;
		StrongBox<AsyncLazy<Object>> lazy = new StrongBox<>();
		AtomicBoolean executed = new AtomicBoolean(false);
		lazy.value = new AsyncLazy<>(() -> {
			Assert.assertFalse(executed.get());
			executed.set(true);
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> Async.awaitAsync(
					lazy.value.getValueAsync(),
					() -> CompletableFuture.completedFuture(new Object())));
		}, jtf);

		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			AsyncAssert.assertThrowsAsync(IllegalStateException.class, () -> lazy.value.getValueAsync()),
			() -> {
				// Do it again, to verify that AsyncLazy recorded the failure and will replay it.
				return Async.awaitAsync(AsyncAssert.assertThrowsAsync(IllegalStateException.class, () -> lazy.value.getValueAsync()));
			});

		asyncTest.join();
	}

	@Test
	public void testGetValueAsyncWithCancellation() {
		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(() ->
			Async.awaitAsync(
				evt,
				() -> CompletableFuture.completedFuture(new GenericParameterHelper(5))));

		CompletableFuture<? extends GenericParameterHelper> task1 = lazy.getValueAsync();
		CompletableFuture<? extends GenericParameterHelper> task2 = lazy.getValueAsync();
		task1.cancel(true);

		// Verify that the future returned from the canceled request actually completes before the value factory does.
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			AsyncAssert.assertCancelsAsync(() -> task1),
			() -> {
				// Now verify that the value factory does actually complete anyway for other callers.
				evt.set();
				return Async.awaitAsync(
					task2,
					task2Result -> {
						Assert.assertEquals(5, task2Result.getData());
						return Futures.completedNull();
					});
			});

		asyncTest.join();
	}

	@Test
	public void testGetValueAsyncWithCancellationToken() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(() ->
			Async.awaitAsync(
				evt,
				() -> CompletableFuture.completedFuture(new GenericParameterHelper(5))));

		CompletableFuture<? extends GenericParameterHelper> task1 = lazy.getValueAsync(cancellationTokenSource.getToken());
		CompletableFuture<? extends GenericParameterHelper> task2 = lazy.getValueAsync();
		cancellationTokenSource.cancel();

		// Verify that the future returned from the canceled request actually completes before the value factory does.
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			// This is not the behavior we want. The cancellation is wrapped in a failed future rather than producing a
			// cancelled future.
			AsyncAssert.assertCancelsAsync(() -> task1),
			() -> {
				// Now verify that the value factory does actually complete anyway for other callers.
				evt.set();
				return Async.awaitAsync(
					task2,
					task2Result -> {
						Assert.assertEquals(5, task2Result.getData());
						return Futures.completedNull();
					});
			});

		asyncTest.join();
	}

	@Test
	public void testGetValueAsyncWithCancellationTokenPreCanceled() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
		cancellationTokenSource.cancel();

		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(() -> CompletableFuture.completedFuture(new GenericParameterHelper(5)));
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			AsyncAssert.assertCancelsAsync(() -> lazy.getValueAsync(cancellationTokenSource.getToken())),
			() -> {
				Assert.assertFalse("Value factory should not have been invoked for a pre-canceled token.", lazy.isValueCreated());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testGetValueAsyncAlreadyCompletedWithCancellationTokenPreCanceled() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
		cancellationTokenSource.cancel();

		AsyncLazy<GenericParameterHelper> lazy = new AsyncLazy<>(() -> CompletableFuture.completedFuture(new GenericParameterHelper(5)));
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			lazy.getValueAsync(),
			() -> Async.awaitAsync(
				// this shouldn't throw canceled because it was already done.
				lazy.getValueAsync(cancellationTokenSource.getToken()),
				result -> {
					Assert.assertEquals(5, result.getData());
					Assert.assertTrue(lazy.isValueCreated());
					Assert.assertTrue(lazy.isValueFactoryCompleted());
					return Futures.completedNull();
				}));

		asyncTest.join();
	}

	@Test
	public void testToStringForUncreatedValue() {
		AsyncLazy<Object> lazy = new AsyncLazy<>(() -> CompletableFuture.completedFuture(null));
		String result = lazy.toString();
		Assert.assertNotNull(result);
		Assert.assertNotEquals("", result);
		Assert.assertFalse(lazy.isValueCreated());
	}

	@Test
	public void testToStringForCreatedValue() {
		AsyncLazy<Integer> lazy = new AsyncLazy<>(() -> CompletableFuture.completedFuture(3));
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			lazy.getValueAsync(),
			value -> {
				String result = lazy.toString();
				Assert.assertEquals(value.toString(), result);
				return Futures.completedNull();
			});
		asyncTest.join();
	}

	@Test
	public void testToStringForFailedValue() {
		AsyncLazy<Integer> lazy = new AsyncLazy<>(
			() -> {
				throw new RuntimeException();
			});
		TplExtensions.forget(lazy.getValueAsync());
		Assert.assertTrue(lazy.isValueCreated());
		String result = lazy.toString();
		Assert.assertNotNull(result);
		Assert.assertNotEquals("", result);
	}

	/**
	 * Verifies that even after the value factory has been invoked its dependency on the Main thread can be satisfied by
	 * someone synchronously blocking on the Main thread that is also interested in its value.
	 */
	@Test
	public void testValueFactoryRequiresMainThreadHeldByOther() throws Exception {
		JoinableFutureContext context = this.initializeJFCAndSC();
		JoinableFutureFactory jtf = context.getFactory();

		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		AsyncLazy<Object> lazy = new AsyncLazy<>(() ->
			Async.awaitAsync(
				// use an event here to ensure it won't resume till the Main thread is blocked.
				evt,
				() -> CompletableFuture.completedFuture(new Object())),
			jtf);

		CompletableFuture<?> resultTask = lazy.getValueAsync();
		Assert.assertFalse(resultTask.isDone());

		JoinableFutureCollection collection = context.createCollection();
		JoinableFutureFactory someRandomPump = context.createFactory(collection);
		someRandomPump.run(() -> {
			// setting this event allows the value factory to resume, once it can get the Main thread.
			evt.set();

			// The interesting bit we're testing here is that
			// the value factory has already been invoked.  It cannot
			// complete until the Main thread is available and we're blocking
			// the Main thread waiting for it to complete.
			// This will deadlock unless the AsyncLazy joins
			// the value factory's async pump with the currently blocking one.
			return Async.awaitAsync(
				lazy.getValueAsync(),
				value -> {
					Assert.assertNotNull(value);
					return Futures.completedNull();
				});
		});

		// Now that the value factory has completed, the earlier acquired
		// task should have no problem completing.
		resultTask.get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Test
	public void testValueFactoryRequiresMainThreadHeldByOtherSync_SpecifyJff() throws Exception {
		testValueFactoryRequiresMainThreadHeldByOtherSync(true);
	}

	@Test
	public void testValueFactoryRequiresMainThreadHeldByOtherSync_NoSpecifyJff() throws Exception {
		testValueFactoryRequiresMainThreadHeldByOtherSync(false);
	}

	/**
	 * Verifies that no deadlock occurs if the value factory synchronously blocks while switching to the UI thread.
	 */
	private void testValueFactoryRequiresMainThreadHeldByOtherSync(boolean passJtfToLazyCtor) throws Exception {
		SynchronizationContext ctxt = SingleThreadedSynchronizationContext.create();
		SynchronizationContext.setSynchronizationContext(ctxt);
		JoinableFutureContext context = new JoinableFutureContext();
		JoinableFutureFactory asyncPump = context.getFactory();
		Thread originalThread = Thread.currentThread();

		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		AsyncLazy<Object> lazy = new AsyncLazy<>(
			() -> {
				// It is important that no await appear before this JFF.run call, since
				// we're testing that the value factory is not invoked while the AsyncLazy
				// holds a private lock that would deadlock when called from another thread.
				asyncPump.run(() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync(getTimeoutToken())));
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> CompletableFuture.completedFuture(new Object()));
			},
			// mix it up to exercise all the code paths in the ctor.
			passJtfToLazyCtor ? asyncPump : null);

		CompletableFuture<?> backgroundRequest = Futures.supplyAsync(() -> Async.awaitAsync(lazy.getValueAsync()));

		// Give the background thread time to call GetValueAsync(), but it doesn't yield (when the test was written).
		Thread.sleep(ASYNC_DELAY.toMillis());
		CompletableFuture<?> foregroundRequest = lazy.getValueAsync();

		Frame frame = SingleThreadedSynchronizationContext.newFrame();
		CompletableFuture<?> combinedTask = CompletableFuture.allOf(foregroundRequest, backgroundRequest);
		TplExtensions.withTimeout(combinedTask, UNEXPECTED_TIMEOUT).thenRun(() -> frame.setContinue(false));
		SingleThreadedSynchronizationContext.pushFrame(ctxt, frame);

		// Ensure that the test didn't simply timeout, and that the individual tasks did not throw.
		Assert.assertTrue(foregroundRequest.isDone());
		Assert.assertTrue(backgroundRequest.isDone());
		Assert.assertSame(foregroundRequest.join(), backgroundRequest.join());
	}

	/**
	 * Verifies that no deadlock occurs if the value factory synchronously blocks while switching to the UI thread and
	 * the UI thread then starts a JFF.run that wants it too.
	 */
	@Test
	public void testValueFactoryRequiresMainThreadHeldByOtherInJFFRun() throws Exception {
		SynchronizationContext ctxt = SingleThreadedSynchronizationContext.create();
		SynchronizationContext.setSynchronizationContext(ctxt);
		JoinableFutureContext context = new JoinableFutureContext();
		JoinableFutureFactory asyncPump = context.getFactory();
		Thread originalThread = Thread.currentThread();

		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		AsyncLazy<Object> lazy = new AsyncLazy<>(
			() -> {
				// It is important that no await appear before this JTF.Run call, since
				// we're testing that the value factory is not invoked while the AsyncLazy
				// holds a private lock that would deadlock when called from another thread.
				asyncPump.run(() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync(getTimeoutToken())));
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> CompletableFuture.completedFuture(new Object()));
			},
			asyncPump);

		CompletableFuture<?> backgroundRequest = Futures.supplyAsync(() -> Async.awaitAsync(lazy.getValueAsync()));

		// Give the background thread time to call getValueAsync(), but it doesn't yield (when the test was written).
		Thread.sleep(ASYNC_DELAY.toMillis());
		asyncPump.run(() -> Async.awaitAsync(
			lazy.getValueAsync(getTimeoutToken()),
			foregroundValue -> Async.awaitAsync(
				backgroundRequest,
				backgroundValue -> {
					Assert.assertSame(foregroundValue, backgroundValue);
					return Futures.completedNull();
				})));
	}

//        [Fact(Skip = "Hangs. This test documents a deadlock scenario that is not fixed (by design, IIRC).")]
//        public async Task ValueFactoryRequiresReadLockHeldByOther()
//        {
//            var lck = new AsyncReaderWriterLock();
//            var readLockAcquiredByOther = new AsyncManualResetEvent();
//            var writeLockWaitingByOther = new AsyncManualResetEvent();
//
//            var lazy = new AsyncLazy<object>(
//                async delegate
//                {
//                    await writeLockWaitingByOther;
//                    using (await lck.ReadLockAsync())
//                    {
//                        return new object();
//                    }
//                });
//
//            var writeLockTask = Task.Run(async delegate
//            {
//                await readLockAcquiredByOther;
//                var writeAwaiter = lck.WriteLockAsync().GetAwaiter();
//                writeAwaiter.OnCompleted(delegate
//                {
//                    using (writeAwaiter.GetResult())
//                    {
//                    }
//                });
//                writeLockWaitingByOther.Set();
//            });
//
//            // Kick off the value factory without any lock context.
//            var resultTask = lazy.GetValueAsync();
//
//            using (await lck.ReadLockAsync())
//            {
//                readLockAcquiredByOther.Set();
//
//                // Now request the lazy task again.
//                // This would traditionally deadlock because the value factory won't
//                // be able to get its read lock while a write lock is waiting (for us to release ours).
//                // This unit test verifies that the AsyncLazy<T> class can avoid deadlocks in this case.
//                await lazy.GetValueAsync();
//            }
//        }

	@NotNull
	private JoinableFutureContext initializeJFCAndSC() {
		SynchronizationContext.setSynchronizationContext(SingleThreadedSynchronizationContext.create());
		return new JoinableFutureContext();
	}

}
