// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;

enum Futures {
	;

	private static final CompletableFuture<?> COMPLETED_NULL;

	static {
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

		COMPLETED_NULL.complete(null);
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
}
