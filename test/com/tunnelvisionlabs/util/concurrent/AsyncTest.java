// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class AsyncTest extends TestBase {

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
}
