// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

/**
 * Copied from Microsoft/vs-threading@14f77875.
 */
public class AsyncAutoResetEventTest extends TestBase {
	private AsyncAutoResetEvent event = new AsyncAutoResetEvent();

	@Test
	public void testSingleThreadedPulse() {
		AtomicInteger i = new AtomicInteger(0);
		CompletableFuture<Void> future = Async.whileAsync(
			() -> i.get() < 5,
			() -> {
				try {
					CompletableFuture<Void> t = event.waitAsync();
					Assert.assertFalse(t.isDone());
					event.set();
					return Async.finallyAsync(
						Async.awaitAsync(t),
						() -> i.incrementAndGet());
				} catch (Throwable ex) {
					i.incrementAndGet();
					throw ex;
				}
			});

		future.join();
	}

	@Test
	public void testMultipleSetOnlySignalsOnce() {
		this.event.set();
		this.event.set();
		CompletableFuture<Void> future = Async.awaitAsync(
			event.waitAsync(),
			() -> {
				CompletableFuture<Void> t = event.waitAsync();
				Assert.assertFalse(t.isDone());
				return Async.awaitAsync(
					Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT),
					() -> {
						Assert.assertFalse(t.isDone());
						return Futures.completedNull();
					});
			});

		future.join();
	}

	@Test
	public void testOrderPreservingQueue() {
		List<CompletableFuture<Void>> waiters = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			waiters.add(event.waitAsync());
		}

		AtomicInteger i = new AtomicInteger(0);
		CompletableFuture<Void> future = Async.whileAsync(
			() -> i.get() < waiters.size(),
			() -> {
				event.set();
				return Async.finallyAsync(
					Async.awaitAsync(waiters.get(i.get())),
					() -> i.incrementAndGet());
			});

		future.join();
	}

	/**
	 * Verifies that inlining continuations do not have to complete execution before {@link AsyncAutoResetEvent#set()}
	 * returns.
	 */
	@Test
	public void testSetReturnsBeforeInlinedContinuations() throws Exception {
		CompletableFuture<Void> setReturned = new CompletableFuture<>();
		CompletableFuture<Void> inlinedContinuation = event.waitAsync().whenComplete((result, exception) -> {
			try {
				// Arrange to synchronously block the continuation until set() has returned,
				// which would deadlock if set() does not return until inlined continuations complete.
				setReturned.get(ASYNC_DELAY, ASYNC_DELAY_UNIT);
			} catch (InterruptedException | ExecutionException | TimeoutException ex) {
				throw new CompletionException(ex);
			}
		});

		event.set();
		setReturned.complete(null);
		inlinedContinuation.get(ASYNC_DELAY, ASYNC_DELAY_UNIT);
	}

	/**
	 * Verifies that inlining continuations works when the option is set.
	 */
	@Test
	public void testSetInlinesContinuationsUnderSwitch() throws Exception {
		event = new AsyncAutoResetEvent(/*allowInliningAwaiters:*/true);
		Thread settingThread = Thread.currentThread();
		final AtomicBoolean setReturned = new AtomicBoolean(false);
		CompletableFuture<Void> inlinedContinuation = event.waitAsync().whenComplete((result, exception) -> {
			// Arrange to synchronously block the continuation until set() has returned,
			// which would deadlock if set() does not return until inlined continuations complete.
			Assert.assertFalse(setReturned.get());
			Assert.assertSame(settingThread, Thread.currentThread());
		});

		event.set();
		setReturned.set(true);
		Assert.assertTrue(inlinedContinuation.isDone());
		// rethrow any exceptions in the continuation
		inlinedContinuation.get(ASYNC_DELAY, ASYNC_DELAY_UNIT);
	}

	@Test
	public void testWaitAsync_WithCancellation_DoesNotClaimSignal() {
		CompletableFuture<Void> waitFuture = event.waitAsync();
		Assert.assertFalse(waitFuture.isDone());

		// Cancel the request and ensure that it propagates to the task.
		waitFuture.cancel(true);
		try {
			waitFuture.join();
			Assert.fail("Future was expected to be cancelled.");
		} catch (CancellationException ex) {
		}

		// Now set the event and verify that a future waiter gets the signal immediately.
		event.set();
		waitFuture = event.waitAsync();
		Assert.assertTrue(waitFuture.isDone());
		Assert.assertFalse(waitFuture.isCompletedExceptionally());
	}

//        [Fact]
//        public void WaitAsync_WithCancellationToken_PrecanceledDoesNotClaimExistingSignal()
//        {
//            // We construct our own pre-canceled token so that we can do
//            // a meaningful identity check later.
//            var tokenSource = new CancellationTokenSource();
//            tokenSource.Cancel();
//            var token = tokenSource.Token;
//
//            // Verify that a pre-set signal is not reset by a canceled wait request.
//            this.evt.Set();
//            try
//            {
//                this.evt.WaitAsync(token).GetAwaiter().GetResult();
//                Assert.True(false, "Task was expected to transition to a canceled state.");
//            }
//            catch (OperationCanceledException ex)
//            {
//                if (!TestUtilities.IsNet45Mode)
//                {
//                    Assert.Equal(token, ex.CancellationToken);
//                }
//            }
//
//            // Verify that the signal was not acquired.
//            Task waitTask = this.evt.WaitAsync();
//            Assert.Equal(TaskStatus.RanToCompletion, waitTask.Status);
//        }

        @Test
        public void testWaitAsync_Canceled_DoesNotInlineContinuations()
        {
            CompletableFuture<Void> future = event.waitAsync();
            verifyDoesNotInlineContinuations(future, () -> future.cancel(true));
        }

        @Test
        public void testWaitAsync_Canceled_DoesInlineContinuations()
        {
            event = new AsyncAutoResetEvent(/*allowInliningAwaiters:*/ true);
            CompletableFuture<Void> future = event.waitAsync();
            verifyCanInlineContinuations(future, () -> future.cancel(true));
        }

//        /// <summary>
//        /// Verifies that long-lived, uncanceled CancellationTokens do not result in leaking memory.
//        /// </summary>
//        [Fact, Trait("TestCategory", "FailsInCloudTest")]
//        public void WaitAsync_WithCancellationToken_DoesNotLeakWhenNotCanceled()
//        {
//            var cts = new CancellationTokenSource();
//
//            this.CheckGCPressure(
//                () =>
//                {
//                    this.evt.WaitAsync(cts.Token);
//                    this.evt.Set();
//                },
//                500);
//        }
//
//        /// <summary>
//        /// Verifies that long-lived, uncanceled CancellationTokens do not result in leaking memory.
//        /// </summary>
//        [Fact, Trait("TestCategory", "FailsInCloudTest")]
//        public void WaitAsync_WithCancellationToken_DoesNotLeakWhenCanceled()
//        {
//            this.CheckGCPressure(
//                () =>
//                {
//                    var cts = new CancellationTokenSource();
//                    this.evt.WaitAsync(cts.Token);
//                    cts.Cancel();
//                },
//                1000);
//        }
}
