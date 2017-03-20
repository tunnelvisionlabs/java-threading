// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * General shared object pools.
 *
 * <p>
 * Use this shared pool if only concern is reducing object allocations. If perf of an object pool itself is also a
 * concern, use {@link ObjectPool} directly.</p>
 *
 * <p>
 * For example, if you want to create a million of small objects within a second, use the {@link ObjectPool} directly.
 * it should have much less overhead than using this.</p>
 */
enum SharedPools {
	;

	private static final ObjectPool<StrongBox<?>> STRONG_BOX_POOL = new ObjectPool<>(
		() -> new StrongBox<>(),
		null,
		box -> box.value = null);
	private static final ConcurrentHashMap<Class<?>, ObjectPool<?>> BIG_POOLS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Class<?>, ObjectPool<?>> NORMAL_POOLS = new ConcurrentHashMap<>();

	/**
	 * Pool that uses default constructor with 100 elements pooled.
	 * 
	 * @param <T> The type of the object pool.
	 * @param clazz The {@link Class} describing the type of the object pool.
	 * @return A default big object pool.
	 */
	public static <T> ObjectPool<T> bigDefault(@NotNull Class<T> clazz) {
		return getOrCreate(BIG_POOLS, clazz, 100);
	}

	/**
	 * Pool that uses default constructor with 20 elements pooled.
	 *
	 * @param <T> The type of the object pool.
	 * @param clazz The {@link Class} describing the type of the object pool.
	 * @return A default object pool.
	 */
	public static <T> ObjectPool<T> normal(@NotNull Class<T> clazz) {
		return getOrCreate(NORMAL_POOLS, clazz, 20);
	}

	public static <T> ObjectPool<StrongBox<T>> strongBox() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		ObjectPool<StrongBox<T>> strongBoxPool = (ObjectPool<StrongBox<T>>)(ObjectPool<?>)STRONG_BOX_POOL;
		return strongBoxPool;
	}

	@NotNull
	private static <T> ObjectPool<T> getOrCreate(@NotNull ConcurrentHashMap<Class<?>, ObjectPool<?>> cache, @NotNull Class<T> clazz, int size) {
		ObjectPool<?> result = cache.get(clazz);
		if (result == null) {
			Supplier<T> factory = () -> {
				try {
					return clazz.newInstance();
				} catch (InstantiationException | IllegalAccessException ex) {
					throw new UnsupportedOperationException(ex);
				}
			};

			cache.putIfAbsent(clazz, new ObjectPool<>(factory, size));
			result = cache.get(clazz);
		}

		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		ObjectPool<T> typedResult = (ObjectPool<T>)result;
		return typedResult;
	}
}
