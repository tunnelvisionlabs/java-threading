// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.UUID;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class GenericParameterHelper {
	private final int data;

	public GenericParameterHelper() {
		this.data = UUID.randomUUID().hashCode();
	}

	public GenericParameterHelper(int data) {
		this.data = data;
	}

	public final int getData() {
		return data;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof GenericParameterHelper)) {
			return false;
		}

		return data == ((GenericParameterHelper)obj).data;
	}

	@Override
	public int hashCode() {
		return data;
	}
}
