// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.function.Function;

enum Functions {
	;

	private static final Function<Object, Object> IDENTITY = (Object t) -> t;

	@NotNull
	public static <T> Function<T, T> identity() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		Function<T, T> result = (Function<T, T>)IDENTITY;
		return result;
	}
}
