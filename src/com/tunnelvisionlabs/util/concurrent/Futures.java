// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

enum Futures {
	;

	private static final CompletableFuture<?> COMPLETED_CANCELLED;
	private static final CompletableFuture<?> COMPLETED_NULL;

	static {
		COMPLETED_CANCELLED = new CompletableFuture<Object>() {
			@Override
			public void obtrudeValue(Object value) {
				throw new UnsupportedOperationException("Not supported");
			}

			@Override
			public void obtrudeException(Throwable ex) {
				throw new UnsupportedOperationException("Not supported");
			}
		};

		COMPLETED_NULL = new CompletableFuture<Object>() {
			@Override
			public void obtrudeValue(Object value) {
				throw new UnsupportedOperationException("Not supported");
			}

			@Override
			public void obtrudeException(Throwable ex) {
				throw new UnsupportedOperationException("Not supported");
			}
		};

		COMPLETED_CANCELLED.cancel(false);
		COMPLETED_NULL.complete(null);
	}

	@NotNull
	public static <T> CompletableFuture<T> completedCancelled() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		CompletableFuture<T> result = (CompletableFuture<T>)COMPLETED_CANCELLED;
		return result;
	}

	@NotNull
	public static <T> CompletableFuture<T> completedFailed(@NotNull Throwable ex) {
		CompletableFuture<T> result = new CompletableFuture<>();
		result.completeExceptionally(ex);
		return result;
	}

	@NotNull
	public static <T> CompletableFuture<T> completedNull() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		CompletableFuture<T> result = (CompletableFuture<T>)COMPLETED_NULL;
		return result;
	}

	public static <T> CompletableFuture<T> fromException(@NotNull Throwable ex) {
		if (ex instanceof CancellationException) {
			return completedCancelled();
		} else {
			return completedFailed(ex);
		}
	}

	@NotNull
	public static <T> CompletableFuture<T> nonCancellationPropagating(@NotNull CompletableFuture<T> future) {
		CompletableFuture<T> wrapper = new CompletableFuture<>();
		future.whenComplete((result, exception) -> {
			if (future.isCancelled()) {
				wrapper.cancel(true);
			} else if (exception != null) {
				wrapper.completeExceptionally(exception);
			} else {
				wrapper.complete(result);
			}
		});

		return wrapper;
	}
}
