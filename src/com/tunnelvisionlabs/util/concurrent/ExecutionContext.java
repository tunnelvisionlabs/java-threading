// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

class ExecutionContext {
	private static final ThreadLocal<Object> SUPPRESS_FLOW = new ThreadLocal<>();

	private final CallContext callContext;

	public ExecutionContext(CallContext callContext) {
		this.callContext = callContext;
	}

	public static ExecutionContext capture() {
		return new ExecutionContext(CallContext.getCurrent().createCopy());
	}

	public static boolean isFlowSuppressed() {
		return SUPPRESS_FLOW.get() != null;
	}

	public static void restoreFlow() {
		if (!isFlowSuppressed()) {
			throw new IllegalStateException("Execution flow is not suppressed.");
		}

		SUPPRESS_FLOW.remove();
	}

	public static AsyncFlowControl suppressFlow() {
		if (isFlowSuppressed()) {
			throw new IllegalStateException("Execution flow is already suppressed.");
		}

		SUPPRESS_FLOW.set(Boolean.TRUE);
		return new AsyncFlowControl();
	}

	public static <T> void run(@NotNull ExecutionContext executionContext, @NotNull Consumer<T> callback, T state) {
		CallContext originalCallContext = CallContext.getCurrent();
		try {
			CallContext.setCallContext(executionContext.callContext);
			callback.accept(state);
		} finally {
			CallContext.setCallContext(originalCallContext);
		}
	}

	@NotNull
	public ExecutionContext createCopy() {
		return new ExecutionContext(callContext.createCopy());
	}

	@NotNull
	public static Runnable wrap(@NotNull Runnable runnable) {
		ExecutionContext executionContext = capture();
		return () -> {
			run(executionContext, Runnable::run, runnable);
		};
	}

	@NotNull
	public static <T, U> Function<T, U> wrap(@NotNull Function<T, U> function) {
		ExecutionContext executionContext = capture();
		return t -> {
			StrongBox<U> result = new StrongBox<>();
			run(executionContext, state -> result.value = function.apply(t), null);
			return result.value;
		};
	}

	@NotNull
	public static <T> Supplier<T> wrap(@NotNull Supplier<T> supplier) {
		ExecutionContext executionContext = capture();
		return () -> {
			StrongBox<T> result = new StrongBox<>();
			run(executionContext, state -> result.value = supplier.get(), null);
			return result.value;
		};
	}

}
