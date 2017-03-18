// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

enum Requires {
	;

	public static void notNull(@NotNull Object value, String parameterName) {
		if (value == null) {
			throw new NullPointerException(parameterName + " cannot be null");
		}
	}

	public static void range(boolean condition, String parameterName) {
		if (!condition) {
			throw new IllegalArgumentException(parameterName + " is out of range");
		}
	}
}
