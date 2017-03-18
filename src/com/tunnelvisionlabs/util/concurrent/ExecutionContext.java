// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

class ExecutionContext {
	private static final ThreadLocal<Object> SUPPRESS_FLOW = new ThreadLocal<>();
	private static final SynchronizationContext INHERIT = new SynchronizationContext() {
		@Override
		public SynchronizationContext createCopy() {
			return this;
		}

		@Override
		public <T> void post(Consumer<T> callback, T state) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> void send(Consumer<T> callback, T state) {
			throw new UnsupportedOperationException();
		}
	};

	private final CallContext callContext;
	private final SynchronizationContext synchronizationContext;

	public ExecutionContext(CallContext callContext, SynchronizationContext synchronizationContext) {
		this.callContext = callContext;
		this.synchronizationContext = synchronizationContext;
	}

	public static ExecutionContext capture() {
		return new ExecutionContext(CallContext.getCurrent().createCopy(), SynchronizationContext.getCurrent());
	}

	private static ExecutionContext capture(boolean captureSynchronizationContext) {
		return new ExecutionContext(CallContext.getCurrent().createCopy(), captureSynchronizationContext ? SynchronizationContext.getCurrent() : INHERIT);
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
			SynchronizationContext originalSynchronizationContext = SynchronizationContext.getCurrent();
			try {
				if (executionContext.synchronizationContext != INHERIT) {
					SynchronizationContext.setSynchronizationContext(executionContext.synchronizationContext);
				}

				callback.accept(state);
			} finally {
				SynchronizationContext.setSynchronizationContext(originalSynchronizationContext);
			}
		} finally {
			CallContext.setCallContext(originalCallContext);
		}
	}

	public ExecutionContext createCopy() {
		throw new UnsupportedOperationException("Not implemented");
	}

	@NotNull
	public static Runnable wrap(@NotNull Runnable runnable) {
		ExecutionContext executionContext = capture(false);
		return () -> {
			run(executionContext, state -> runnable.run(), null);
		};
	}

	@NotNull
	public static <T, U> Function<T, U> wrap(@NotNull Function<T, U> function) {
		ExecutionContext executionContext = capture(false);
		return t -> {
			List<U> result = new ArrayList<>();
			run(executionContext, state -> result.add(function.apply(t)), null);
			return result.get(0);
		};
	}

	@NotNull
	public static <T> Supplier<T> wrap(@NotNull Supplier<T> supplier) {
		ExecutionContext executionContext = capture(false);
		return () -> {
			List<T> result = new ArrayList<>();
			run(executionContext, state -> result.add(supplier.get()), null);
			return result.get(0);
		};
	}

}
