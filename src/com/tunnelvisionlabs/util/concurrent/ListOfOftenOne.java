// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe collection optimized for very small number of non-null elements.
 *
 * <p>The collection is alloc-free for storage, retrieval and enumeration of collection sizes of 0 or 1. Beyond that
 * causes one allocation for an immutable array that contains the entire collection.</p>
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 *
 * @param <T> The type of elements to be stored.
 */
class ListOfOftenOne<T> implements Iterable<T> {
	/**
	 * The single value or array of values stored by this collection. Null if empty.
	 */
	private final AtomicReference<Object> value = new AtomicReference<>();

	@NotNull
	public ListOfOftenOne<T> createCopy() {
		ListOfOftenOne<T> result = new ListOfOftenOne<>();
		result.value.set(this.value.get());
		return result;
	}

	/**
	 * Returns an iterator for a current snapshot of the collection.
	 */
	@Override
	public final Iterator<T> iterator() {
		return new IteratorImpl<>(value.get());
	}

	/**
	 * Adds an element to the collection.
	 */
	public final void add(@NotNull T value) {
		Object priorValue;
		boolean replaced;
		do {
			priorValue = this.value.get();
			Object newValue = combine(priorValue, value);
			replaced = this.value.compareAndSet(priorValue, newValue);
		} while (!replaced);
	}

	/**
	 * Removes an element from the collection.
	 */
	public final void remove(@NotNull T value) {
		Object priorValue;
		boolean replaced;
		do {
			priorValue = this.value.get();
			Object newValue = remove(priorValue, value);
			replaced = this.value.compareAndSet(priorValue, newValue);
		} while (!replaced);
	}

	/**
	 * Checks for reference equality between the specified value and an element of this collection.
	 *
	 * @param value The value to check for.
	 * @return {@code true} if a match is found; {@code false} otherwise.
	 */
	public final boolean contains(T value) {
		for (T item : this) {
			if (item == value) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Atomically clears the collection's contents and returns an iterator over the prior contents.
	 */
	final Iterator<T> iterateAndClear() {
		// Iteration is atomically destructive.
		Object iteratedValue = value.getAndSet(null);
		return new IteratorImpl<>(iteratedValue);
	}

	/**
	 * Combines the previous contents of the collection with one additional value.
	 *
	 * @param baseValue The collection's prior contents.
	 * @param value The value to add to the collection.
	 * @return The new value to store as the collection.
	 */
	@NotNull
	private static <T> Object combine(@Nullable Object baseValue, @NotNull T value) {
		Requires.notNull(value, "value");

		if (baseValue == null) {
			return value;
		}

		if (baseValue instanceof Object[]) {
			Object[] oldArray = (Object[])baseValue;
			Object[] newArray = Arrays.copyOf(oldArray, oldArray.length + 1);
			newArray[newArray.length - 1] = value;
			return newArray;
		}

		return new Object[] { baseValue, value };
	}

	/**
	 * Removes a value from contents of the collection.
	 *
	 * @param baseValue The collection's prior contents.
	 * @param value The value to remove from the collection.
	 * @return The new value to store as the collection.
	 */
	private static <T> Object remove(Object baseValue, T value) {
		if (baseValue == value || baseValue == null) {
			return null;
		}

		if (!(baseValue instanceof Object[])) {
			// baseValue is a single element not equal to value
			return baseValue;
		}

		Object[] oldArray = (Object[])baseValue;
		for (int i = 0; i < oldArray.length; i++) {
			if (oldArray[i] == value) {
				if (oldArray.length == 2) {
					return oldArray[i == 0 ? 1 : 0];
				}

				// Shift remaining elements and return
				for (int j = i + 1; j < oldArray.length; j++) {
					oldArray[j - 1] = oldArray[j];
				}

				return Arrays.copyOf(oldArray, oldArray.length - 1);
			}
		}

		return baseValue;
	}

	public static class IteratorImpl<T> implements Iterator<T> {

		private static final int INDEX_BEFORE_FIRST_ARRAY_ELEMENT = -1;
		private static final int INDEX_SINGLE_ELEMENT = -2;
		private static final int INDEX_BEFORE_SINGLE_ELEMENT = -3;

		private final Object iteratedValue;
		private int currentIndex;

		public IteratorImpl(@Nullable Object iteratedValue) {
			this.iteratedValue = iteratedValue;
			if (iteratedValue instanceof Object[]) {
				this.currentIndex = INDEX_BEFORE_FIRST_ARRAY_ELEMENT;
			} else {
				this.currentIndex = INDEX_BEFORE_SINGLE_ELEMENT;
			}
		}

		@Override
		public boolean hasNext() {
			switch (currentIndex) {
				case INDEX_SINGLE_ELEMENT:
					return false;

				case INDEX_BEFORE_SINGLE_ELEMENT:
					return iteratedValue != null;

				case INDEX_BEFORE_FIRST_ARRAY_ELEMENT:
					return true;

				default:
					// currentIndex is the index of the item previously returned
					return ((Object[])iteratedValue).length > currentIndex + 1;
			}
		}

		@Override
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		public T next() {
			switch (currentIndex) {
				case INDEX_SINGLE_ELEMENT:
					break;

				case INDEX_BEFORE_SINGLE_ELEMENT:
					if (iteratedValue != null) {
						currentIndex = INDEX_SINGLE_ELEMENT;
						return (T)iteratedValue;
					}

					break;

				case INDEX_BEFORE_FIRST_ARRAY_ELEMENT:
				default:
					Object[] data = (Object[])iteratedValue;
					if (currentIndex + 1 < data.length) {
						return (T)data[++currentIndex];
					}

					break;
			}

			throw new NoSuchElementException();
		}

	}

//        public struct Enumerator : IEnumerator<T>
//        {
//            private const int IndexBeforeFirstArrayElement = -1;
//            private const int IndexSingleElement = -2;
//            private const int IndexBeforeSingleElement = -3;
//
//            private readonly object enumeratedValue;
//
//            private int currentIndex;
//
//            internal Enumerator(object enumeratedValue)
//            {
//                this.enumeratedValue = enumeratedValue;
//                this.currentIndex = 0;
//                this.Reset();
//            }
//
//            public T Current
//            {
//                get
//                {
//                    if (this.currentIndex == IndexBeforeFirstArrayElement || this.currentIndex == IndexBeforeSingleElement)
//                    {
//                        throw new InvalidOperationException();
//                    }
//
//                    return this.currentIndex == IndexSingleElement
//                        ? (T)this.enumeratedValue
//                        : ((T[])this.enumeratedValue)[this.currentIndex];
//                }
//            }
//
//            object System.Collections.IEnumerator.Current
//            {
//                get { return this.Current; }
//            }
//
//            public void Dispose()
//            {
//            }
//
//            public bool MoveNext()
//            {
//                if (this.currentIndex == IndexBeforeSingleElement && this.enumeratedValue != null)
//                {
//                    this.currentIndex = IndexSingleElement;
//                    return true;
//                }
//
//                if (this.currentIndex == IndexSingleElement)
//                {
//                    return false;
//                }
//
//                if (this.currentIndex == IndexBeforeFirstArrayElement)
//                {
//                    this.currentIndex = 0;
//                    return true;
//                }
//
//                var array = (T[])this.enumeratedValue;
//                if (this.currentIndex >= 0 && this.currentIndex < array.Length)
//                {
//                    this.currentIndex++;
//                    return this.currentIndex < array.Length;
//                }
//
//                return false;
//            }
//
//            public void Reset()
//            {
//                this.currentIndex = this.enumeratedValue is T[] ? IndexBeforeFirstArrayElement : IndexBeforeSingleElement;
//            }
//        }
}
