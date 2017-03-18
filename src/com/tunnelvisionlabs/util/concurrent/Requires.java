// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

enum Requires {
	;

	public static void notNull(@NotNull Object value, String name) {
		if (value == null) {
			throw new NullPointerException(name + " cannot be null");
		}
	}
}
