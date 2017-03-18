// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

enum Verify {
	;

	public static void failOperation(@NotNull String message) {
		throw new IllegalStateException(message);
	}
}
