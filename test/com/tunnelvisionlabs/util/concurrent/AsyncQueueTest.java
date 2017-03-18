// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncQueueTest extends TestBase {
	private final AsyncQueue<GenericParameterHelper> queue = new AsyncQueue<>();

	@Test
	public void testJustInitialized() {
		Assert.assertEquals(0, queue.size());
		Assert.assertTrue(queue.isEmpty());
		Assert.assertFalse(queue.getFuture().isDone());
	}

	@Test
	public void testAdd() {
		GenericParameterHelper value = new GenericParameterHelper(1);
		queue.add(value);
		Assert.assertEquals(1, queue.size());
		Assert.assertFalse(queue.isEmpty());
	}

	@Test
	public void testTryAdd() {
		GenericParameterHelper value = new GenericParameterHelper(1);
		Assert.assertTrue(queue.tryAdd(value));
		Assert.assertEquals(1, queue.size());
		Assert.assertFalse(queue.isEmpty());
	}

	@Test
	public void testPeekReturnsNullOnEmptyQueue() {
		Assert.assertNull(queue.peek());
	}

//        [Fact]
//        public void TryPeek()
//        {
//            GenericParameterHelper value;
//            Assert.False(this.queue.TryPeek(out value));
//            Assert.Null(value);
//
//            var enqueuedValue = new GenericParameterHelper(1);
//            this.queue.Enqueue(enqueuedValue);
//            GenericParameterHelper peekedValue;
//            Assert.True(this.queue.TryPeek(out peekedValue));
//            Assert.Same(enqueuedValue, peekedValue);
//        }

	@Test
	public void testPeek() {
		GenericParameterHelper addedValue = new GenericParameterHelper(1);
		queue.add(addedValue);
		GenericParameterHelper peekedValue = queue.peek();
		Assert.assertSame(addedValue, peekedValue);

		// Peeking again should yield the same result.
		peekedValue = queue.peek();
		Assert.assertSame(addedValue, peekedValue);

		// Adding another element shouldn't change the peeked value.
		GenericParameterHelper secondValue = new GenericParameterHelper(2);
		queue.add(secondValue);
		peekedValue = queue.peek();
		Assert.assertSame(addedValue, peekedValue);

		GenericParameterHelper polledValue = queue.poll();
		Assert.assertNotNull(polledValue);
		Assert.assertSame(addedValue, polledValue);

		peekedValue = this.queue.peek();
		Assert.assertSame(secondValue, peekedValue);
	}

	@Test
	public void testPollAsyncCompletesSynchronouslyForNonEmptyQueue() {
		GenericParameterHelper addedValue = new GenericParameterHelper(1);
		queue.add(addedValue);
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync();
		Assert.assertTrue(pollFuture.isDone());

		CompletableFuture<Void> future = Async.awaitAsync(
			pollFuture,
			polledValue -> {
				Assert.assertSame(addedValue, polledValue);
				Assert.assertEquals(0, queue.size());
				Assert.assertTrue(queue.isEmpty());
				return Futures.completedNull();
			});

		future.join();
	}

	@Test
	public void testPollAsyncNonBlockingWait() {
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync();
		Assert.assertFalse(pollFuture.isDone());

		GenericParameterHelper addedValue = new GenericParameterHelper(1);
		queue.add(addedValue);

		CompletableFuture<Void> future = Async.awaitAsync(
			pollFuture,
			polledValue -> {
				Assert.assertSame(addedValue, polledValue);

				Assert.assertEquals(0, queue.size());
				Assert.assertTrue(queue.isEmpty());
				return Futures.completedNull();
			});
		future.join();
	}

	@Test
	public void testPollAsyncCancelledBeforeComplete() {
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync();
		Assert.assertFalse(pollFuture.isDone());

		Assert.assertTrue(pollFuture.cancel(true));

		try {
			thrown.expect(CancellationException.class);
			pollFuture.join();
		} finally {
			GenericParameterHelper addedValue = new GenericParameterHelper(1);
			queue.add(addedValue);

			Assert.assertEquals(1, this.queue.size());
		}
	}

	@Test
	public void testPollAsyncCancelledTokenBeforeComplete() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync(cancellationTokenSource.getToken());
		Assert.assertFalse(pollFuture.isDone());

		cancellationTokenSource.cancel();

		try {
			thrown.expect(CancellationException.class);
			pollFuture.join();
		} finally {
			GenericParameterHelper addedValue = new GenericParameterHelper(1);
			queue.add(addedValue);

			Assert.assertEquals(1, this.queue.size());
		}
	}

	@Test
	public void testPollAsyncPrecancelled() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
		cancellationTokenSource.cancel();
		CompletableFuture<GenericParameterHelper> dequeueTask = queue.pollAsync(cancellationTokenSource.getToken());
		Assert.assertTrue(dequeueTask.isDone());
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			AsyncAssert.assertCancelsAsync(() -> dequeueTask),
			() -> {
				GenericParameterHelper enqueuedValue = new GenericParameterHelper(1);
				this.queue.add(enqueuedValue);

				Assert.assertEquals(1, this.queue.size());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testPollAsyncCancelledAfterComplete() {
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync();
		Assert.assertFalse(pollFuture.isDone());

		GenericParameterHelper addedValue = new GenericParameterHelper(1);
		queue.add(addedValue);

		Assert.assertFalse(pollFuture.cancel(true));

		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			pollFuture,
			polledValue -> {
				Assert.assertTrue(queue.isEmpty());
				Assert.assertSame(addedValue, polledValue);
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testMultiplePollers() {
		List<CompletableFuture<GenericParameterHelper>> pollers = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			pollers.add(queue.pollAsync());
		}

		for (int i = 0; i < pollers.size(); i++) {
			long completedCount = pollers.stream().filter(CompletableFuture::isDone).count();
			Assert.assertEquals(i, completedCount);
			queue.add(new GenericParameterHelper(i));
		}

		for (int i = 0; i < pollers.size(); i++) {
			final int index = i;
			Assert.assertTrue(pollers.stream().anyMatch(d -> d.join().getData() == index));
		}
	}

	@Test
	public void testMultiplePollersCancelled() {
		List<CompletableFuture<GenericParameterHelper>> pollers = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			pollers.add(queue.pollAsync());
		}

		// cancel some of them
		for (int i = 0; i < pollers.size(); i += 2) {
			pollers.get(i).cancel(true);
		}

		for (int i = 0; i < pollers.size(); i++) {
			Assert.assertEquals(i % 2 == 0, pollers.get(i).isCancelled());

			if (!pollers.get(i).isCancelled()) {
				this.queue.add(new GenericParameterHelper(i));
			}
		}

		Assert.assertTrue(pollers.stream().allMatch(CompletableFuture::isDone));
	}

	@Test
	public void testMultiplePollersCancelledToken() {
		CancellationTokenSource[] cancellationTokenSources = new CancellationTokenSource[2];
		for (int i = 0; i < cancellationTokenSources.length; i++) {
			cancellationTokenSources[i] = new CancellationTokenSource();
		}

		List<CompletableFuture<GenericParameterHelper>> pollers = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			pollers.add(queue.pollAsync(cancellationTokenSources[i % 2].getToken()));
		}

		// cancel some of them
		cancellationTokenSources[0].cancel();

		for (int i = 0; i < pollers.size(); i++) {
			Assert.assertEquals(i % 2 == 0, pollers.get(i).isCancelled());

			if (!pollers.get(i).isCancelled()) {
				this.queue.add(new GenericParameterHelper(i));
			}
		}

		Assert.assertTrue(pollers.stream().allMatch(CompletableFuture::isDone));
	}

	@Test
	public void testPoll() {
		GenericParameterHelper addedValue = new GenericParameterHelper(1);
		queue.add(addedValue);
		GenericParameterHelper polledValue = queue.poll();
		Assert.assertNotNull(polledValue);
		Assert.assertSame(addedValue, polledValue);
		Assert.assertEquals(0, queue.size());
		Assert.assertTrue(queue.isEmpty());

		polledValue = queue.poll();
		Assert.assertNull(polledValue);
		Assert.assertEquals(0, queue.size());
		Assert.assertTrue(queue.isEmpty());
	}

	@Test
	public void testComplete() {
		queue.complete();
		Assert.assertTrue(queue.getFuture().isDone());
	}

	@Test
	public void testCompleteThenPollAsync() {
		GenericParameterHelper enqueuedValue = new GenericParameterHelper(1);
		queue.add(enqueuedValue);
		queue.complete();
		Assert.assertFalse(queue.getFuture().isDone());

		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			queue.pollAsync(),
			dequeuedValue -> {
				Assert.assertSame(enqueuedValue, dequeuedValue);
				Assert.assertTrue(queue.getFuture().isDone());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testCompleteThenPoll() {
		GenericParameterHelper enqueuedValue = new GenericParameterHelper(1);
		queue.add(enqueuedValue);
		queue.complete();
		Assert.assertFalse(queue.getFuture().isDone());

		GenericParameterHelper dequeuedValue = queue.poll();
		Assert.assertNotNull(dequeuedValue);
		Assert.assertSame(enqueuedValue, dequeuedValue);
		Assert.assertTrue(queue.getFuture().isDone());
	}

	@Test
	public void testCompleteWhilePollersWaiting() {
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync();
		queue.complete();
		Assert.assertTrue(queue.getFuture().isDone());
		Assert.assertTrue(pollFuture.isCancelled());
	}

	@Test
	public void testCompletedQueueRejectsAdd() {
		queue.complete();

		try {
			thrown.expect(IllegalStateException.class);
			queue.add(new GenericParameterHelper(1));
		} finally {
			Assert.assertTrue(queue.isEmpty());
		}
	}

	@Test
	public void testCompletedQueueRejectsTryAdd() {
		queue.complete();
		Assert.assertFalse(queue.tryAdd(new GenericParameterHelper(1)));
		Assert.assertTrue(queue.isEmpty());
	}

//        [Fact]
//        public void DequeueCancellationAndCompletionStress()
//        {
//            var queue = new AsyncQueue<GenericParameterHelper>();
//            queue.Complete();
//
//            // This scenario was proven to cause a deadlock before a bug was fixed.
//            // This scenario should remain to protect against regressions.
//            int iterations = 0;
//            var stopwatch = Stopwatch.StartNew();
//            while (stopwatch.ElapsedMilliseconds < TestTimeout / 2)
//            {
//                var cts = new CancellationTokenSource();
//                using (var barrier = new Barrier(2))
//                {
//                    var otherThread = Task.Run(delegate
//                    {
//                        barrier.SignalAndWait();
//                        queue.DequeueAsync(cts.Token);
//                        barrier.SignalAndWait();
//                    });
//
//                    barrier.SignalAndWait();
//                    cts.Cancel();
//                    barrier.SignalAndWait();
//
//                    otherThread.Wait();
//                }
//
//                iterations++;
//            }
//
//            this.Logger.WriteLine("Iterations: {0}", iterations);
//        }

	@Test
	public void testNoLockHeldForCancellationContinuation() {
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync();
		CompletableFuture<Void> handled = pollFuture.handle((result, exception) -> {
			try {
				ForkJoinPool.commonPool().submit(ExecutionContext.wrap(() -> {
					// Add presumably requires a private lock internally.
					// Since we're calling it on a different thread than the
					// blocking cancellation continuation, this should deadlock
					// if and only if the queue is holding a lock while invoking
					// our cancellation continuation (which they shouldn't be doing).
					queue.add(new GenericParameterHelper(1));
				})).get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
				return null;
			} catch (InterruptedException | ExecutionException | TimeoutException ex) {
				throw new CompletionException(ex);
			}
		});

		pollFuture.cancel(true);

		handled.join();
		Assert.assertEquals(1, queue.size());
	}

	@Test
	public void testNoLockHeldForCancellationTokenContinuation() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
		CompletableFuture<GenericParameterHelper> pollFuture = queue.pollAsync(cancellationTokenSource.getToken());
		CompletableFuture<Void> handled = pollFuture.handle((result, exception) -> {
			try {
				ForkJoinPool.commonPool().submit(ExecutionContext.wrap(() -> {
					// Add presumably requires a private lock internally.
					// Since we're calling it on a different thread than the
					// blocking cancellation continuation, this should deadlock
					// if and only if the queue is holding a lock while invoking
					// our cancellation continuation (which they shouldn't be doing).
					queue.add(new GenericParameterHelper(1));
				})).get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
				return null;
			} catch (InterruptedException | ExecutionException | TimeoutException ex) {
				throw new CompletionException(ex);
			}
		});

		cancellationTokenSource.cancel();

		handled.join();
		Assert.assertEquals(1, queue.size());
	}

	@Test
	public void testOnAddedNotAlreadyDispatched() {
		DerivedQueue<Integer> queue = new DerivedQueue<>();
		AtomicBoolean callbackFired = new AtomicBoolean(false);
		queue.onAddedHandler = (value, alreadyDispatched) -> {
			Assert.assertEquals(5, (int)value);
			Assert.assertFalse(alreadyDispatched);
			callbackFired.set(true);
		};

		queue.add(5);
		Assert.assertTrue(callbackFired.get());
	}

	@Test
	public void testOnAddedAlreadyDispatched() {
		DerivedQueue<Integer> queue = new DerivedQueue<>();
		AtomicBoolean callbackFired = new AtomicBoolean(false);
		queue.onAddedHandler = (value, alreadyDispatched) -> {
			Assert.assertEquals(5, (int)value);
			Assert.assertTrue(alreadyDispatched);
			callbackFired.set(true);
		};

		CompletableFuture<Integer> poller = queue.pollAsync();
		queue.add(5);
		Assert.assertTrue(callbackFired.get());
		Assert.assertTrue(poller.isDone());
	}

	@Test
	public void testOnPolled() {
		DerivedQueue<Integer> queue = new DerivedQueue<>();
		AtomicBoolean callbackFired = new AtomicBoolean(false);
		queue.onPolledHandler = value -> {
			Assert.assertEquals(5, (int)value);
			callbackFired.set(true);
		};

		queue.add(5);
		Integer polledValue = queue.poll();
		Assert.assertNotNull(polledValue);
		Assert.assertTrue(callbackFired.get());
	}

	@Test
	public void testOnCompletedInvoked() {
		DerivedQueue<GenericParameterHelper> queue = new DerivedQueue<>();
		AtomicInteger invoked = new AtomicInteger(0);
		queue.onCompletedHandler = invoked::incrementAndGet;
		queue.complete();
		Assert.assertEquals(1, invoked.get());

		// Call it again to make sure it's only invoked once.
		queue.complete();
		Assert.assertEquals(1, invoked.get());
	}

	//[Fact, Trait("GC", "true"), Trait("TestCategory", "FailsInCloudTest")]
	@Test
	@Ignore("GC test is unreliable")
	public void testUnusedQueueGCPressure() {
		checkGCPressure(() -> {
			AsyncQueue<GenericParameterHelper> queue = new AsyncQueue<>();
			queue.complete();
			Assert.assertTrue(queue.isCompleted());
		},
			/*maxBytesAllocated:*/ 80); // NOTE: .NET has this at 81
	}

	private static class DerivedQueue<T> extends AsyncQueue<T> {

		BiConsumer<T, Boolean> onAddedHandler;
		Runnable onCompletedHandler;
		Consumer<T> onPolledHandler;

		@Override
		protected void onAdded(T value, boolean alreadyDispatched) {
			super.onAdded(value, alreadyDispatched);

			if (onAddedHandler != null) {
				onAddedHandler.accept(value, alreadyDispatched);
			}
		}

		@Override
		protected void onPolled(T value) {
			super.onPolled(value);

			if (onPolledHandler != null) {
				onPolledHandler.accept(value);
			}
		}

		@Override
		protected void onCompleted() {
			super.onCompleted();

			if (onCompletedHandler != null) {
				onCompletedHandler.run();
			}
		}
	}

}
