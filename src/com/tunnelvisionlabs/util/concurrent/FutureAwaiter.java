// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;

public class FutureAwaiter<T> implements Awaiter<T> {
	private final CompletableFuture<T> future;

	public FutureAwaiter(@NotNull CompletableFuture<T> future) {
		this.future = future;
	}

	@Override
	public boolean isDone() {
		return future.isDone();
	}

	@Override
	public T getResult() {
		return future.join();
	}

	@Override
	public void onCompleted(Runnable continuation) {
		future.whenComplete((result, exception) -> {
			continuation.run();
		});
	}
}
