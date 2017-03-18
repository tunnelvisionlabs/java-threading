// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.function.Function;
import java.util.function.Supplier;

class ExecutionContext {

	@NotNull
	public static Runnable wrap(@NotNull Runnable runnable) {
		return () -> runnable.run();
	}

	@NotNull
	public static <T, U> Function<T, U> wrap(@NotNull Function<T, U> function) {
		return t -> function.apply(t);
	}

	@NotNull
	public static <T> Supplier<T> wrap(@NotNull Supplier<T> supplier) {
		return () -> supplier.get();
	}

}
