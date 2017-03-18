// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;

public final class FutureAwaitable<T> implements Awaitable<T> {
	private final CompletableFuture<T> future;
	private final boolean continueOnCapturedContext;

	public FutureAwaitable(@NotNull CompletableFuture<T> future) {
		this(future, true);
	}

	public FutureAwaitable(@NotNull CompletableFuture<T> future, boolean continueOnCapturedContext) {
		this.future = future;
		this.continueOnCapturedContext = continueOnCapturedContext;
	}

	@NotNull
	@Override
	public Awaiter<T> getAwaiter() {
		return new FutureAwaiter<>(future, continueOnCapturedContext);
	}
}
