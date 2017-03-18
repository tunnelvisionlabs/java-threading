// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.isA;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncCountdownEventTest extends TestBase {
	@Test
	public void testInitialCountZero() {
		AsyncCountdownEvent event = new AsyncCountdownEvent(0);
		CompletableFuture<Void> future = Async.awaitAsync(event.waitAsync());
		future.join();
	}

	@Test
	public void testCountdownFromOnePresignaled() {
		CompletableFuture<Void> future = Async.awaitAsync(preSignalHelperAsync(1));
		future.join();
	}

	@Test
	public void testCountdownFromOnePostSignaled() {
		CompletableFuture<Void> future = Async.awaitAsync(postSignalHelperAsync(1));
		future.join();
	}

	@Test
	public void testCountdownFromTwoPresignaled() {
		CompletableFuture<Void> future = Async.awaitAsync(preSignalHelperAsync(2));
		future.join();
	}

	@Test
	public void testCountdownFromTwoPostSignaled() {
		CompletableFuture<Void> future = Async.awaitAsync(postSignalHelperAsync(2));
		future.join();
	}

	@Test
	public void testSignalAndWaitFromOne() {
		AsyncCountdownEvent event = new AsyncCountdownEvent(1);
		CompletableFuture<Void> future = Async.awaitAsync(event.signalAndWaitAsync());
		future.join();
	}

	@Test
	public void testSignalAndWaitFromTwo() {
		AsyncCountdownEvent evt = new AsyncCountdownEvent(2);

		CompletableFuture<Void> first = evt.signalAndWaitAsync();
		Assert.assertFalse(first.isDone());

		CompletableFuture<Void> second = evt.signalAndWaitAsync();
		CompletableFuture<Void> future = first.thenCombine(second, (a, b) -> null);
		future.join();
	}

	@Test
	public void testSignalAndWaitSynchronousBlockDoesNotHang() throws Exception {
		SynchronizationContext.setSynchronizationContext(SingleThreadedSynchronizationContext.create());
		AsyncCountdownEvent evt = new AsyncCountdownEvent(1);
		evt.signalAndWaitAsync().get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Verifies that the exception is returned in a future rather than thrown from the asynchronous method.
	 */
	@Test
	public void testSignalAsyncReturnsFailedFutureOnError() {
		AsyncCountdownEvent event = new AsyncCountdownEvent(0);
		@SuppressWarnings("deprecation")
		CompletableFuture<Void> result = event.signalAsync();
		Assert.assertTrue(result.isCompletedExceptionally());
		Assert.assertFalse(result.isCancelled());

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(IllegalStateException.class));
		result.join();
	}

	/**
	 * Verifies that the exception is returned in a future rather than thrown from the asynchronous method.
	 */
	@Test
	public void testSignalAndWaitAsyncReturnsFailedFutureOnError() {
		AsyncCountdownEvent event = new AsyncCountdownEvent(0);
		CompletableFuture<Void> result = event.signalAndWaitAsync();
		Assert.assertTrue(result.isCompletedExceptionally());
		Assert.assertFalse(result.isCancelled());

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(IllegalStateException.class));
		result.join();
	}

	/**
	 * Verifies that the exception is returned in a future rather than thrown from the synchronous method.
	 */
	@Test
	public void testSignalThrowsOnError() {
		AsyncCountdownEvent event = new AsyncCountdownEvent(0);
		thrown.expect(IllegalStateException.class);
		event.signal();
	}

	@NotNull
	private CompletableFuture<Void> preSignalHelperAsync(int initialCount) {
		AsyncCountdownEvent event = new AsyncCountdownEvent(initialCount);
		for (int i = 0; i < initialCount; i++) {
			event.signal();
		}

		return Async.awaitAsync(event.waitAsync());
	}

	@NotNull
	private CompletableFuture<Void> postSignalHelperAsync(int initialCount) {
		AsyncCountdownEvent event = new AsyncCountdownEvent(initialCount);
		CompletableFuture<Void> waiter = event.waitAsync();

		for (int i = 0; i < initialCount; i++) {
			event.signal();
		}

		return Async.awaitAsync(waiter);
	}
}
