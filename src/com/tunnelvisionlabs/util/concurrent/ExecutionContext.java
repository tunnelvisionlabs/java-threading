// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

class ExecutionContext {
	private static final AsyncLocal<?>[] EMPTY_NOTIFICATIONS = { };
	static final ExecutionContext DEFAULT = new ExecutionContext();

	private final AsyncLocalValueMap localValues;
	private final AsyncLocal<?>[] localChangeNotifications;
	private final boolean isFlowSuppressed;

	private ExecutionContext() {
		localValues = AsyncLocalValueMap.EMPTY;
		localChangeNotifications = EMPTY_NOTIFICATIONS;
		isFlowSuppressed = false;
	}

	private ExecutionContext(AsyncLocalValueMap localValues, AsyncLocal<?>[] localChangeNotifications, boolean isFlowSuppressed) {
		this.localValues = localValues;
		this.localChangeNotifications = localChangeNotifications;
		this.isFlowSuppressed = isFlowSuppressed;
	}

	public static ExecutionContext capture() {
		ExecutionContext executionContext = ThreadProperties.getExecutionContext(Thread.currentThread());
		if (executionContext == null) {
			return DEFAULT;
		}

		if (executionContext.isFlowSuppressed) {
			return null;
		}

		return executionContext;
	}

	private ExecutionContext shallowClone(boolean isFlowSuppressed) {
		assert isFlowSuppressed != this.isFlowSuppressed;
		
		if (!isFlowSuppressed
			&& localValues == DEFAULT.localValues
			&& localChangeNotifications == DEFAULT.localChangeNotifications) {
			// implies the default context
			return null;
		}
		
		return new ExecutionContext(localValues, localChangeNotifications, isFlowSuppressed);
	}

	public static AsyncFlowControl suppressFlow() {
		Thread currentThread = Thread.currentThread();
		ExecutionContext executionContext = ThreadProperties.getExecutionContext(currentThread);
		if (executionContext == null) {
			executionContext = DEFAULT;
		}

		if (executionContext.isFlowSuppressed) {
			throw new IllegalStateException("Execution flow is already suppressed.");
		}

		executionContext = executionContext.shallowClone(true);
		AsyncFlowControl asyncFlowControl = new AsyncFlowControl();
		ThreadProperties.setExecutionContext(currentThread, executionContext);
		asyncFlowControl.initialize(currentThread);
		return asyncFlowControl;
	}

	public static void restoreFlow() {
		Thread currentThread = Thread.currentThread();
		ExecutionContext executionContext = ThreadProperties.getExecutionContext(currentThread);
		if (executionContext == null || !executionContext.isFlowSuppressed) {
			throw new IllegalStateException("Execution flow is not suppressed.");
		}

		ThreadProperties.setExecutionContext(currentThread, executionContext.shallowClone(false));
	}

	public static boolean isFlowSuppressed() {
		Thread currentThread = Thread.currentThread();
		ExecutionContext executionContext = ThreadProperties.getExecutionContext(currentThread);
		return executionContext != null && executionContext.isFlowSuppressed;
	}

	public static <T> void run(@NotNull ExecutionContext executionContext, @NotNull Consumer<T> callback, T state) {
		Requires.notNull(executionContext, "executionContext");

		Thread currentThread = Thread.currentThread();
		ExecutionContextSwitcher executionContextSwitcher = new ExecutionContextSwitcher();
		try {
			establishCopyOnWriteScope(currentThread, executionContextSwitcher);
			ExecutionContext.restore(currentThread, executionContext);
			callback.accept(state);
		} finally {
			executionContextSwitcher.undo(currentThread);
		}
	}

	static void restore(Thread currentThread, ExecutionContext executionContext) {
		assert currentThread == Thread.currentThread();
		
		ExecutionContext previous = ThreadProperties.getExecutionContext(currentThread);
		if (previous == null) {
			previous = DEFAULT;
		}
		
		ThreadProperties.setExecutionContext(currentThread, executionContext);

		// New EC could be null if that's what ECS.Undo saved off.
		// For the purposes of dealing with context change, treat this as the default EC
		if (executionContext == null) {
			executionContext = DEFAULT;
		}

		if (previous != executionContext) {
			onContextChanged(previous, executionContext);
		}
	}

	static void establishCopyOnWriteScope(Thread currentThread, ExecutionContextSwitcher executionContextSwitcher) {
		assert currentThread == Thread.currentThread();
		
		executionContextSwitcher.executionContext = ThreadProperties.getExecutionContext(currentThread);
		executionContextSwitcher.synchronizationContext = ThreadProperties.getSynchronizationContext(currentThread);
	}

	private static void onContextChanged(ExecutionContext previous, ExecutionContext current) {
		assert previous != null;
		assert current != null;
		assert previous != current;

		for (AsyncLocal<?> local : previous.localChangeNotifications) {
			Object previousValue = previous.localValues.get(local);
			Object currentValue = current.localValues.get(local);

			if (previousValue != currentValue) {
				// Safe for our use in this dispatch
				@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
				AsyncLocal<Object> localAsObj = (AsyncLocal<Object>)local;
				localAsObj.onValueChanged(previousValue, currentValue, true);
			}
		}

		if (current.localChangeNotifications != previous.localChangeNotifications) {
			try {
				for (AsyncLocal<?> local : current.localChangeNotifications) {
					// If the local has a value in the previous context, we already fired the event for that local
					// in the code above.
					Object previousValue = previous.localValues.get(local);
					if (previousValue == null) {
						Object currentValue = current.localValues.get(local);

						if (previousValue != currentValue) {
							// Safe for our use in this dispatch
							@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
							AsyncLocal<Object> localAsObj = (AsyncLocal<Object>)local;

							localAsObj.onValueChanged(previousValue, currentValue, true);
						}
					}
				}
			} catch (Exception ex) {
//                    Environment.FailFast(
//                        Environment.GetResourceString("ExecutionContext_ExceptionInAsyncLocalNotification"), 
//                        ex);
			}
		}
	}

	static <T> T getLocalValue(AsyncLocal<? extends T> local) {
		ExecutionContext current = ThreadProperties.getExecutionContext(Thread.currentThread());
		if (current == null) {
			return null;
		}

		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		T result = (T)current.localValues.get(local);
		return result;
	}

	static <T> void setLocalValue(AsyncLocal<T> local, T newValue, boolean needChangeNotifications) {
		ExecutionContext current = ThreadProperties.getExecutionContext(Thread.currentThread());
		if (current == null) {
			current = DEFAULT;
		}

		T previousValue = current.localValues.get(local);
		boolean hadPreviousValue = previousValue != null;

		if (previousValue == newValue) {
			return;
		}

		AsyncLocalValueMap newValues = current.localValues.put(local, newValue);

		//
		// Either copy the change notification array, or create a new one, depending on whether we need to add a new item.
		//
		AsyncLocal<?>[] newChangeNotifications = current.localChangeNotifications;
		if (needChangeNotifications) {
			if (hadPreviousValue) {
				assert Arrays.asList(newChangeNotifications).indexOf(local) >= 0;
			} else {
				int newNotificationIndex = newChangeNotifications.length;
				newChangeNotifications = Arrays.copyOf(newChangeNotifications, newNotificationIndex + 1);
				newChangeNotifications[newNotificationIndex] = local;
			}
		}

		ThreadProperties.setExecutionContext(
			Thread.currentThread(),
			new ExecutionContext(newValues, newChangeNotifications, current.isFlowSuppressed));

		if (needChangeNotifications) {
			local.onValueChanged(previousValue, newValue, false);
		}
	}

	@NotNull
	public final ExecutionContext createCopy() {
		// since CoreCLR's ExecutionContext is immutable, we don't need to create copies.
		return this;
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
