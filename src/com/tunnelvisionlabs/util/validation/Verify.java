// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.validation;

public enum Verify {
	;

	public static void operation(boolean condition, @NotNull String message) {
		if (!condition) {
			failOperation(message);
		}
	}

	public static void failOperation(@NotNull String message) {
		throw new IllegalStateException(message);
	}
}
