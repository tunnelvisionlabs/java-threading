// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

public class AsyncLocalValueChangedEventArgs<T> {
	private final T previousValue;
	private final T currentValue;
	private final boolean threadContextChanged;

	public AsyncLocalValueChangedEventArgs(T previousValue, T currentValue, boolean threadContextChanged) {
		this.previousValue = previousValue;
		this.currentValue = currentValue;
		this.threadContextChanged = threadContextChanged;
	}

	public T getPreviousValue() {
		return previousValue;
	}

	public T getCurrentValue() {
		return currentValue;
	}

	public boolean isThreadContextChanged() {
		return threadContextChanged;
	}
}
