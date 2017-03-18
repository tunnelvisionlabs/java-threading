// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.UUID;

/**
 * Stores references such that they are available for retrieval in the same call context.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 *
 * @param <T> The type of value to store.
 */
public class AsyncLocal<T> {
	private final String contextKey = UUID.randomUUID().toString();

	public final T getValue() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		T result = (T)CallContext.getData(contextKey);
		return result;
	}

	public final void setValue(T value) {
		if (value == null) {
			CallContext.freeNamedDataSlot(contextKey);
		} else {
			CallContext.setData(contextKey, value);
		}
	}
}
