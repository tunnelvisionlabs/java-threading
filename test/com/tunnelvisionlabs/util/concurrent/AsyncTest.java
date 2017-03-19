// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.isA;

public class AsyncTest extends TestBase {

	/**
	 * This test is one part of verifying https://github.com/tunnelvisionlabs/java-threading/issues/11.
	 */
	@Test
	public void testAwaitAsyncCompletedThenCancelledFunction() {
		CompletableFuture<Void> cancellationFuture = new CompletableFuture<>();
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			Futures.completedNull(),
			ignored -> cancellationFuture);

		cancellationFuture.cancel(true);

		thrown.expect(CancellationException.class);
		asyncTest.join();
	}

	/**
	 * This test is one part of verifying https://github.com/tunnelvisionlabs/java-threading/issues/11.
	 */
	@Test
	public void testAwaitAsyncCompletedThenCancelledSupplier() {
		CompletableFuture<Void> cancellationFuture = new CompletableFuture<>();
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			Futures.completedNull(),
			() -> cancellationFuture);

		cancellationFuture.cancel(true);

		thrown.expect(CancellationException.class);
		asyncTest.join();
	}

	@Test
	public void testContinuationOrder() {
		AtomicInteger value = new AtomicInteger();
		CompletableFuture<Void> future = new CompletableFuture<>();
		future.thenRun(() -> value.set(1));
		future.thenRun(() -> value.set(2));
		future.complete(null);
		Assert.assertEquals("Continuations are a stack, not a queue.", 1, value.get());
	}

	/**
	 * This test is one part of verifying https://github.com/tunnelvisionlabs/java-threading/issues/11.
	 */
	@Test
	public void testCompletedThenComposeCancelled() {
		CompletableFuture<Void> future = new CompletableFuture<>();
		future.complete(null);
		CompletableFuture<Void> composed = future.thenCompose(s -> Futures.completedCancelled());

		Assert.assertTrue(composed.isDone());
		Assert.assertTrue(composed.isCompletedExceptionally());

		if (Async.REQUIRE_UNWRAP_FOR_COMPLETED_ANTECEDENT) {
			Assert.assertFalse("The current runtime does not preserve cancellation when the antecedent is pre-completed", composed.isCancelled());

			thrown.expect(CompletionException.class);
			thrown.expectCause(isA(CancellationException.class));
			composed.join();
		} else {
			Assert.assertTrue(composed.isCancelled());

			thrown.expect(CancellationException.class);
			composed.join();
		}
	}

	/**
	 * This test is one part of verifying https://github.com/tunnelvisionlabs/java-threading/issues/11.
	 */
	@Test
	public void testNotCompletedThenComposeCancelled() {
		CompletableFuture<Void> future = new CompletableFuture<>();
		CompletableFuture<Void> composed = future.thenCompose(s -> Futures.completedCancelled());
		future.complete(null);

		Assert.assertTrue(composed.isDone());
		Assert.assertTrue(composed.isCompletedExceptionally());
		Assert.assertFalse("Cancellation is not preserved when the antecedent is not completed when thenCompose is called", composed.isCancelled());

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(CancellationException.class));
		composed.join();
	}

	@Test
	public void testSynchronousExecutionContextFlowsBeforeAwaiterGetResult() {
		CompletableFuture<Void> asyncTest = Async.runAsync(() -> {
			AsyncLocal<Integer> value = new AsyncLocal<>();
			Awaiter<Void> awaiter = new Awaiter<Void>() {
				@Override
				public boolean isDone() {
					return true;
				}

				@Override
				public void onCompleted(Runnable continuation) {
					Assert.fail("Should not be reachable.");
				}

				@Override
				public Void getResult() {
					value.setValue(1);
					return null;
				}
			};
			Awaitable<Void> awaitable = () -> awaiter;
			return Async.awaitAsync(
				awaitable,
				() -> {
					Assert.assertEquals((Integer)1, value.getValue());
					return Futures.completedNull();
				});
		});

		asyncTest.join();
	}

	@Test
	public void testAsynchronousExecutionContextFlowsBeforeAwaiterGetResult() {
		CompletableFuture<Void> asyncTest = Async.runAsync(() -> {
			AsyncLocal<Integer> value = new AsyncLocal<>();
			Awaiter<Void> awaiter = new Awaiter<Void>() {
				@Override
				public boolean isDone() {
					// Force a yield
					return false;
				}

				@Override
				public void onCompleted(Runnable continuation) {
					Futures.runAsync(continuation);
				}

				@Override
				public Void getResult() {
					value.setValue(1);
					return null;
				}
			};
			Awaitable<Void> awaitable = () -> awaiter;
			return Async.awaitAsync(
				awaitable,
				() -> {
					Assert.assertEquals((Integer)1, value.getValue());
					return Futures.completedNull();
				});
		});

		asyncTest.join();
	}

	@NotNull
	private CompletableFuture<Void> sleepingMethodAsync(@NotNull Duration duration, @NotNull CancellationToken cancellationToken) {
		return Async.runAsync(() -> {
			try (Disposable registration = ThreadingTools.interruptOnCancel(cancellationToken)) {
				try {
					Thread.sleep(duration.toMillis());
				} catch (InterruptedException ex) {
					return Futures.completedCancelled();
				}
			}

			return Futures.completedNull();
		});
	}

	@Test
	public void testCancelSleep() {
		CompletableFuture<Void> asyncTest = Async.runAsync(() -> {
			return Async.awaitAsync(AsyncAssert.assertCancelsAsync(() -> {
				CancellationTokenSource cancellationTokenSource = new CancellationTokenSource(Duration.ofMillis(20));
				return sleepingMethodAsync(Duration.ofMinutes(5), cancellationTokenSource.getToken());
			}));
		});

		asyncTest.join();
	}
}
