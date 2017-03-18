// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

public class FutureAwaiter<T> implements Awaiter<T> {
	private final CompletableFuture<T> future;
	private final boolean continueOnCapturedContext;

	public FutureAwaiter(@NotNull CompletableFuture<T> future, boolean continueOnCapturedContext) {
		this.future = future;
		this.continueOnCapturedContext = continueOnCapturedContext;
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
		Executor executor;
		if (continueOnCapturedContext) {
			SynchronizationContext synchronizationContext = SynchronizationContext.getCurrent();
			if (synchronizationContext != null && synchronizationContext.getClass() != SynchronizationContext.class) {
				executor = synchronizationContext;
			} else {
				executor = ForkJoinPool.commonPool();
			}
		} else {
			executor = ForkJoinPool.commonPool();
		}

		Runnable wrappedContinuation = ExecutionContext.wrap(continuation);
		future.whenCompleteAsync((result, exception) -> continuation.run(), executor);
	}
}
