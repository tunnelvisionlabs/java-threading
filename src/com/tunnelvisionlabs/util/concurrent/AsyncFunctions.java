// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

enum AsyncFunctions {
	;

	private static final Function<Object, CompletableFuture<?>> IDENTITY = CompletableFuture::completedFuture;

	@NotNull
	public static <T> Function<T, CompletableFuture<T>> identity() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		Function<T, CompletableFuture<T>> result = (Function<T, CompletableFuture<T>>)(Object)IDENTITY;
		return result;
	}
}
