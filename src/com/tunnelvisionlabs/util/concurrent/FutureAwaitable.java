// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

public final class FutureAwaitable<T> implements Awaitable<T> {
	private final CompletableFuture<? extends T> future;
	private final boolean continueOnCapturedContext;

	public FutureAwaitable(@NotNull CompletableFuture<? extends T> future) {
		this(future, true);
	}

	public FutureAwaitable(@NotNull CompletableFuture<? extends T> future, boolean continueOnCapturedContext) {
		this.future = future;
		this.continueOnCapturedContext = continueOnCapturedContext;
	}

	@NotNull
	@Override
	public FutureAwaiter getAwaiter() {
		return new FutureAwaiter();
	}

	public class FutureAwaiter implements Awaiter<T>, CriticalNotifyCompletion {
		@Override
		public boolean isDone() {
			return future.isDone();
		}

		@Override
		public T getResult() {
			return future.join();
		}

		@Override
		public void onCompleted(@NotNull Runnable continuation) {
			onCompletedImpl(continuation, true);
		}

		@Override
		public void unsafeOnCompleted(@NotNull Runnable continuation) {
			onCompletedImpl(continuation, false);
		}

		private void onCompletedImpl(@NotNull Runnable continuation, boolean useExecutionContext) {
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

			Runnable wrappedContinuation = useExecutionContext ? ExecutionContext.wrap(continuation) : continuation;
			future.whenCompleteAsync((result, exception) -> wrappedContinuation.run(), executor);
		}
	}
}
