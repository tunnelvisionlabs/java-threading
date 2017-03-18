package com.tunnelvisionlabs.util.concurrent;

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
