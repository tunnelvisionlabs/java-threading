// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.validation;

public enum Report {
	;

	public static void fail(@NotNull String message) {
		// Currently a NOP
	}

	public static void fail(@NotNull String format, Object... params) {
		// Currently a NOP
	}

	public static void reportIf(boolean condition) {
		// Currently a NOP
	}

	public static void reportIf(boolean condition, @NotNull String message) {
		// Currently a NOP
	}

	public static void reportIf(boolean condition, @NotNull String message, Object... params) {
		// Currently a NOP
	}

}
