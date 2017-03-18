// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.Collection;

enum Iterables {
	;

	public static int size(@NotNull Iterable<?> iterable) {
		if (iterable instanceof Collection) {
			return ((Collection<?>)iterable).size();
		}

		int size = 0;
		for (Object o : iterable) {
			size++;
		}

		return size;
	}
}
