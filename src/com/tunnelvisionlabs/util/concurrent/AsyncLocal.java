// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.function.Consumer;

/**
 * Stores references such that they are available for retrieval in the same call context.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 *
 * @param <T> The type of value to store.
 */
public final class AsyncLocal<T> implements IAsyncLocal {
	private final Consumer<? super AsyncLocalValueChangedEventArgs<T>> valueChangedHandler;

	public AsyncLocal() {
		this.valueChangedHandler = null;
	}

	public AsyncLocal(Consumer<? super AsyncLocalValueChangedEventArgs<T>> valueChangedHandler) {
		this.valueChangedHandler = valueChangedHandler;
	}

	public final T getValue() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		T result = (T)ExecutionContext.getLocalValue(this);
		return result;
	}

	public final void setValue(T value) {
		ExecutionContext.setLocalValue(this, value, valueChangedHandler != null);
	}

	@Override
	public final void onValueChanged(Object previousValueObj, Object currentValueObj, boolean contextChanged) {
		assert valueChangedHandler != null;
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		T previousValue = (T)previousValueObj;
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		T currentValue = (T)currentValueObj;
		valueChangedHandler.accept(new AsyncLocalValueChangedEventArgs<>(previousValue, currentValue, contextChanged));
	}
}
