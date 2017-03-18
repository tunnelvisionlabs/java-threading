// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Test;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncManualResetEventTest extends TestBase {
	private final AsyncManualResetEvent event = new AsyncManualResetEvent();

//        [Fact]
//        public void CtorDefaultParameter()
//        {
//            Assert.False(new System.Threading.ManualResetEventSlim().IsSet);
//        }

	@Test
	public void testDefaultSignaledState() {
		Assert.assertTrue(new AsyncManualResetEvent(true).isSet());
		Assert.assertFalse(new AsyncManualResetEvent(false).isSet());
	}

	@Test
	public void testNonBlocking() {
		@SuppressWarnings("deprecation")
		CompletableFuture<Void> future = Async.awaitAsync(
			event.setAsync(),
			() -> {
				Assert.assertTrue(event.waitAsync().isDone());
				return Futures.completedNull();
			});

		future.join();
	}

	/**
	 * Verifies that inlining continuations do not have to complete execution before {@link AsyncManualResetEvent#set()}
	 * returns.
	 */
	@Test
	public void testSetReturnsBeforeInlinedContinuations() throws Exception {
		CompletableFuture<Void> setReturned = new CompletableFuture<>();
		CompletableFuture<Void> inlinedContinuation = event.waitAsync()
			.thenRun(()
				-> {
				try {
					// Arrange to synchronously block the continuation until set() has returned,
					// which would deadlock if set() does not return until inlined continuations complete.
					setReturned.get(ASYNC_DELAY, ASYNC_DELAY_UNIT);
				} catch (InterruptedException | ExecutionException | TimeoutException ex) {
					throw new CompletionException(ex);
				}
			});
		event.set();
		Assert.assertTrue(event.isSet());
		setReturned.complete(null);
		inlinedContinuation.get(ASYNC_DELAY, ASYNC_DELAY_UNIT);
	}

	@Test
	public void testBlocking() {
		event.reset();
		CompletableFuture<Void> result = event.waitAsync();
		Assert.assertFalse(result.isDone());
		event.set();
		CompletableFuture<Void> future = Async.awaitAsync(result);
		future.join();
	}

	@Test
	public void testReset() {
		@SuppressWarnings("deprecation")
		CompletableFuture<Void> future = Async.awaitAsync(
			event.setAsync(),
			() -> {
				event.reset();
				CompletableFuture<Void> result = event.waitAsync();
				Assert.assertFalse(result.isDone());
				return Futures.completedNull();
			});

		future.join();
	}

	@Test
	public void testAwaitable() {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			return Async.awaitAsync(this.event);
		});

		this.event.set();
		task.join();
	}

	@Test
	public void testPulseAllAsync() {
		CompletableFuture<Void> waitFuture = event.waitAsync();
		@SuppressWarnings("deprecation")
		CompletableFuture<Void> pulseFuture = event.pulseAllAsync();

		CompletableFuture<Void> future = Async.awaitAsync(
			pulseFuture,
			() -> {
				Assert.assertFalse(pulseFuture.isCompletedExceptionally());

				Assert.assertTrue(waitFuture.isDone());
				Assert.assertFalse(event.waitAsync().isDone());
				return Futures.completedNull();
			});

		future.join();
	}

	@Test
	public void testPulseAll() {
		CompletableFuture<Void> waitFuture = event.waitAsync();
		event.pulseAll();

		CompletableFuture<Void> future = Async.awaitAsync(
			waitFuture,
			() -> {
				Assert.assertFalse(event.waitAsync().isDone());
				return Futures.completedNull();
			});

		future.join();
	}

	@Test
	@SuppressWarnings("deprecation")
	public void testPulseAllAsyncDoesNotUnblockFutureWaiters() {
		CompletableFuture<Void> future1 = event.waitAsync();
		event.pulseAllAsync();
		CompletableFuture<Void> future2 = event.waitAsync();
		Assert.assertNotSame(future1, future2);
		future1.join();
		Assert.assertFalse(future2.isDone());
	}

	@Test
	public void testPulseAllDoesNotUnblockFutureWaiters() {
		CompletableFuture<Void> future1 = event.waitAsync();
		event.pulseAll();
		CompletableFuture<Void> future2 = event.waitAsync();
		Assert.assertNotSame(future1, future2);
		future1.join();
		Assert.assertFalse(future2.isDone());
	}

	@Test
	public void testSetAsyncThenResetLeavesEventInResetState() {
		// We starve the fork join pool so that if setAsync()
		// does work asynchronously, we'll force it to happen
		// after the reset() method is executed.
		CompletableFuture<Void> asyncTest = Async.usingAsync(
			TestUtilities.starveForkJoinPool(),
			starvation -> {
				// Set and immediately reset the event.
				@SuppressWarnings("deprecation")
				CompletableFuture<Void> setTask = event.setAsync();
				Assert.assertTrue(event.isSet());
				event.reset();
				Assert.assertFalse(event.isSet());

				// At this point, the event should be unset,
				// but allow the setAsync call to finish its work.
				starvation.close();
				return Async.awaitAsync(
					setTask,
					() -> {
						// Verify that the event is still unset.
						// If this fails, then the async nature of setAsync
						// allowed it to "jump" over the reset and leave the event
						// in a set state (which would of course be very bad).
						Assert.assertFalse(event.isSet());
						return Futures.completedNull();
					});
			});

		asyncTest.join();
	}

	@Test
	public void testSetThenPulseAllResetsEvent() {
		event.set();
		event.pulseAll();
		Assert.assertFalse(event.isSet());
	}

//        [Fact]
//        public void SetAsyncCalledTwiceReturnsSameTask()
//        {
//            using (TestUtilities.StarveThreadpool())
//            {
//                Task waitTask = this.evt.WaitAsync();
//#pragma warning disable CS0618 // Type or member is obsolete
//                Task setTask1 = this.evt.SetAsync();
//                Task setTask2 = this.evt.SetAsync();
//#pragma warning restore CS0618 // Type or member is obsolete
//
//                // Since we starved the threadpool, no work should have happened
//                // and we expect the result to be the same, since SetAsync
//                // is supposed to return a Task that signifies that the signal has
//                // actually propagated to the Task returned by WaitAsync earlier.
//                // In fact we'll go so far as to assert the Task itself should be the same.
//                Assert.Same(waitTask, setTask1);
//                Assert.Same(waitTask, setTask2);
//            }
//        }

	@Test
	public void testCancelDoesNotCancelAll() {
		CompletableFuture<Void> future1 = event.waitAsync();
		CompletableFuture<Void> future2 = event.waitAsync();

		// Cancel the first future
		try {
			future1.cancel(true);
			future1.join();
			Assert.fail("Expected a CancellationException");
		} catch (CancellationException ex) {
		}

		// Make sure the second future was not cancelled
		event.set();
		future2.join();
	}
}
