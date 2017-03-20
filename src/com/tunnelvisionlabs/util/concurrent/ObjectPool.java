// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Generic implementation of object pooling pattern with predefined pool size limit. The main purpose is that limited
 * number of frequently used objects can be kept in the pool for further recycling.
 *
 * <p>Notes:</p>
 *
 * <ol>
 * <li>it is not the goal to keep all returned objects. Pool is not meant for storage. If there is no space in the pool,
 * extra returned objects will be dropped.</li>
 * <li>it is implied that if object was obtained from a pool, the caller will return it back in a relatively short time.
 * Keeping checked out objects for long durations is OK, but reduces usefulness of pooling. Just new up your own.</li>
 * </ol>
 *
 * <p>Not returning objects to the pool in not detrimental to the pool's work, but is a bad practice. Rationale: If
 * there is no intent for reusing the object, do not use pool - just use "new".</p>
 *
 * @param <T> The type of the objects in this cache.
 */
class ObjectPool<T> {
	private final AtomicReferenceArray<T> items;

	/**
	 * {@code factory} is stored for the lifetime of the pool. We will call this only when pool needs to expand.
	 * Compared to {@link Class#newInstance()}, {@link Supplier} gives more flexibility to implementers and is faster
	 * than {@link Class#newInstance()}.
	 */
	private final Supplier<? extends T> factory;

	private final Consumer<? super T> allocate;

	private final Consumer<? super T> free;

	/**
	 * Storage for the pool objects. The first item is stored in a dedicated field because we expect to be able to
	 * satisfy most requests from it.
	 */
	private final AtomicReference<T> firstItem = new AtomicReference<>();

	ObjectPool(@NotNull Supplier<? extends T> factory) {
		this(factory, null, null, Runtime.getRuntime().availableProcessors() * 2);
	}

	ObjectPool(@NotNull Supplier<? extends T> factory, int size) {
		this(factory, null, null, size);
	}

	ObjectPool(@NotNull Supplier<? extends T> factory, @Nullable Consumer<? super T> allocate, @Nullable Consumer<? super T> free) {
		this(factory, allocate, free, Runtime.getRuntime().availableProcessors() * 2);
	}

	ObjectPool(@NotNull Supplier<? extends T> factory, @Nullable Consumer<? super T> allocate, @Nullable Consumer<? super T> free, int size) {
		Requires.argument(size >= 1, "size", "The object pool can't be empty");
		this.factory = factory;
		this.allocate = allocate;
		this.free = free;
		this.items = new AtomicReferenceArray<>(size - 1);
	}

	/**
	 * Produces an instance.
	 *
	 * <p>Search strategy is a simple linear probing which is chosen for it cache-friendliness. Note that
	 * {@link #free(Object)} will try to store recycled objects close to the start thus statistically reducing how far
	 * we will typically search.</p>
	 *
	 * @return A (possibly) cached instance of type {@code T}.
	 */
	final T allocate() {
		// PERF: Examine the first element. If that fails, allocateSlow will look at the remaining elements.
		T inst = firstItem.getAndSet(null);
		if (inst == null) {
			inst = allocateSlow();
		}

		if (allocate != null) {
			allocate.accept(inst);
		}

		return inst;
	}

	/**
	 * Returns objects to the pool.
	 *
	 * <p>Search strategy is a simple linear probing which is chosen for it cache-friendliness. Note that
	 * {@link #free(Object)} will try to store recycled objects close to the start thus statistically reducing how far
	 * we will typically search in Allocate.</p>
	 *
	 * @param obj The object to free.
	 */
	final void free(T obj) {
		if (free != null) {
			free.accept(obj);
		}

		if (firstItem == null) {
			// Intentionally not using compareAndSet here.
			// In a worst case scenario two objects may be stored into same slot.
			// It is very unlikely to happen and will only mean that one of the objects will get collected.
			firstItem.lazySet(obj);
		} else {
			freeSlow(obj);
		}
	}

	private T createInstance() {
		T inst = factory.get();
		return inst;
	}

	private T allocateSlow() {
		for (int i = 0; i < items.length(); i++) {
			// Note that the initial read is optimistically not synchronized. That is intentional.
			// We will interlock only when we have a candidate. in a worst case we may miss some
			// recently returned objects. Not a big deal.
			T inst = items.get(i);
			if (inst != null) {
				inst = items.getAndSet(i, null);
				if (inst != null) {
					return inst;
				}
			}
		}

		return createInstance();
	}

	private void freeSlow(T obj) {
		for (int i = 0; i < items.length(); i++) {
			if (items.get(i) == null) {
				// Intentionally not using compareAndSet here.
				// In a worst case scenario two objects may be stored into same slot.
				// It is very unlikely to happen and will only mean that one of the objects will get collected.
				items.lazySet(i, obj);
				break;
			}
		}
	}
}
