// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.validation;

public enum Requires {
	;

	public static <T> T notNull(@NotNull T value, String parameterName) {
		if (value == null) {
			throw new NullPointerException(parameterName + " cannot be null");
		}

		return value;
	}

	public static String notNullOrEmpty(@NotNull String value, String parameterName) {
		notNull(value, parameterName);
		argument(!value.isEmpty(), parameterName, "cannot be empty");
		return value;
	}

	public static void argument(boolean condition, String parameterName, String message) {
		if (!condition) {
			throw new IllegalArgumentException(parameterName + ": " + message);
		}
	}

	public static void range(boolean condition, String parameterName) {
		if (!condition) {
			throw new IllegalArgumentException(parameterName + " is out of range");
		}
	}
}
