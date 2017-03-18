// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.HashMap;
import java.util.Map;

abstract class AsyncLocalValueMap {
	;

	public static final AsyncLocalValueMap EMPTY = new EmptyAsyncLocalValueMap();

	abstract <T> T get(AsyncLocal<T> key);

	abstract <T> AsyncLocalValueMap put(AsyncLocal<T> key, T value);

	private static final class EmptyAsyncLocalValueMap extends AsyncLocalValueMap {

		@Override
		public <T> AsyncLocalValueMap put(AsyncLocal<T> key, T value) {
			// If the value isn't null, then create a new one-element map to store
			// the key/value pair.  If it is null, then we're still empty.
			return value != null
				? new OneElementAsyncLocalValueMap(key, value)
				: this;
		}

		@Override
		public <T> T get(AsyncLocal<T> key) {
			return null;
		}
	}

	// Instance with one key/value pair.
	private static final class OneElementAsyncLocalValueMap extends AsyncLocalValueMap {

		private final AsyncLocal<?> key1;
		private final Object value1;

		public <T> OneElementAsyncLocalValueMap(AsyncLocal<T> key, T value) {
			key1 = key;
			value1 = value;
		}

		@Override
		public <T> AsyncLocalValueMap put(AsyncLocal<T> key, T value) {
			// This cast is safe for our use in copying the map
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			AsyncLocal<Object> key1 = (AsyncLocal<Object>)this.key1;

			if (value != null) {
				// The value is non-null.  If the key matches one already contained in this map,
				// then create a new one-element map with the updated value, otherwise create
				// a two-element map with the additional key/value.
				return key == key1
					? new OneElementAsyncLocalValueMap(key, value)
					: (AsyncLocalValueMap)new TwoElementAsyncLocalValueMap(key1, value1, key, value);
			} else {
				// The value is null.  If the key exists in this map, remove it by downgrading to an empty map.
				// Otherwise, there's nothing to add or remove, so just return this map.
				return key == key1 ? EMPTY : this;
			}
		}

		@Override
		public <T> T get(AsyncLocal<T> key) {
			if (key == key1) {
				@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
				T result = (T)value1;
				return result;
			} else {
				return null;
			}
		}
	}

	// Instance with two key/value pairs.
	private static final class TwoElementAsyncLocalValueMap extends AsyncLocalValueMap {

		private final AsyncLocal<?> key1;
		private final AsyncLocal<?> key2;
		private final Object value1;
		private final Object value2;

		public <T, U> TwoElementAsyncLocalValueMap(AsyncLocal<T> key1, T value1, AsyncLocal<U> key2, U value2) {
			this.key1 = key1;
			this.value1 = value1;
			this.key2 = key2;
			this.value2 = value2;
		}

		@Override
		public <T> AsyncLocalValueMap put(AsyncLocal<T> key, T value) {
			// These casts are safe for our use in copying the map
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			AsyncLocal<Object> key1 = (AsyncLocal<Object>)this.key1;
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			AsyncLocal<Object> key2 = (AsyncLocal<Object>)this.key2;

			if (value != null) {
				// The value is non-null.  If the key matches one already contained in this map,
				// then create a new two-element map with the updated value, otherwise create
				// a three-element map with the additional key/value.
				return key == key1 ? new TwoElementAsyncLocalValueMap(key, value, key2, value2)
					: key == key2 ? new TwoElementAsyncLocalValueMap(key1, value1, key, value)
						: new ThreeElementAsyncLocalValueMap(key1, value1, key2, value2, key, value);
			} else {
				// The value is null.  If the key exists in this map, remove it by downgrading to a one-element map
				// without the key.  Otherwise, there's nothing to add or remove, so just return this map.
				return key == key1 ? new OneElementAsyncLocalValueMap(key2, value2)
					: key == key2 ? new OneElementAsyncLocalValueMap(key1, value1)
						: (AsyncLocalValueMap)this;
			}
		}

		@Override
		public <T> T get(AsyncLocal<T> key) {
			if (key == key1) {
				@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
				T result = (T)value1;
				return result;
			} else if (key == key2) {
				@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
				T result = (T)value2;
				return result;
			} else {
				return null;
			}
		}
	}

	// Instance with three key/value pairs.
	private static final class ThreeElementAsyncLocalValueMap extends AsyncLocalValueMap {

		private final AsyncLocal<?> key1;
		private final AsyncLocal<?> key2;
		private final AsyncLocal<?> key3;
		private final Object value1;
		private final Object value2;
		private final Object value3;

		public <T, U, V> ThreeElementAsyncLocalValueMap(AsyncLocal<T> key1, T value1, AsyncLocal<U> key2, U value2, AsyncLocal<V> key3, V value3) {
			this.key1 = key1;
			this.value1 = value1;
			this.key2 = key2;
			this.value2 = value2;
			this.key3 = key3;
			this.value3 = value3;
		}

		@Override
		public <T> AsyncLocalValueMap put(AsyncLocal<T> key, T value) {
			// These casts are safe for our use in copying the map
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			AsyncLocal<Object> key1 = (AsyncLocal<Object>)this.key1;
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			AsyncLocal<Object> key2 = (AsyncLocal<Object>)this.key2;
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			AsyncLocal<Object> key3 = (AsyncLocal<Object>)this.key3;

			if (value != null) {
				// The value is non-null.  If the key matches one already contained in this map,
				// then create a new three-element map with the updated value.
				if (key == key1) {
					return new ThreeElementAsyncLocalValueMap(key, value, key2, value2, key3, value3);
				}
				if (key == key2) {
					return new ThreeElementAsyncLocalValueMap(key1, value1, key, value, key3, value3);
				}
				if (key == key3) {
					return new ThreeElementAsyncLocalValueMap(key1, value1, key2, value2, key, value);
				}

				// The key doesn't exist in this map, so upgrade to a multi map that contains
				// the additional key/value pair.
				MultiElementAsyncLocalValueMap multi = new MultiElementAsyncLocalValueMap(4);
				multi.unsafeStore(0, key1, value1);
				multi.unsafeStore(1, key2, value2);
				multi.unsafeStore(2, key3, value3);
				multi.unsafeStore(3, key, value);
				return multi;
			} else {
				// The value is null.  If the key exists in this map, remove it by downgrading to a two-element map
				// without the key.  Otherwise, there's nothing to add or remove, so just return this map.
				return key == key1 ? new TwoElementAsyncLocalValueMap(key2, value2, key3, value3)
					: key == key2 ? new TwoElementAsyncLocalValueMap(key1, value1, key3, value3)
						: key == key3 ? new TwoElementAsyncLocalValueMap(key1, value1, key2, value2)
							: this;
			}
		}

		@Override
		public <T> T get(AsyncLocal<T> key) {
			if (key == key1) {
				@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
				T result = (T)value1;
				return result;
			} else if (key == key2) {
				@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
				T result = (T)value2;
				return result;
			} else if (key == key3) {
				@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
				T result = (T)value3;
				return result;
			} else {
				return null;
			}
		}
	}

	// Instance with up to 16 key/value pairs.
	private static final class MultiElementAsyncLocalValueMap extends AsyncLocalValueMap {

		static final int MAX_MULTI_ELEMENTS = 16;
		private final AsyncLocal<?>[] keys;
		private final Object[] values;

		MultiElementAsyncLocalValueMap(int count) {
			assert count <= MAX_MULTI_ELEMENTS;
			keys = new AsyncLocal<?>[count];
			values = new Object[count];
		}

		<T> void unsafeStore(int index, AsyncLocal<T> key, T value) {
			assert index < keys.length;
			keys[index] = key;
			values[index] = value;
		}

		@Override
		public <T> AsyncLocalValueMap put(AsyncLocal<T> key, T value) {
			// This cast is safe for our use in copying the map
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			AsyncLocal<Object>[] keys = (AsyncLocal<Object>[])this.keys;

			// Find the key in this map.
			for (int i = 0; i < keys.length; i++) {
				if (key == keys[i]) {
					// The key is in the map.  If the value isn't null, then create a new map of the same
					// size that has all of the same pairs, with this new key/value pair overwriting the old.
					if (value != null) {
						MultiElementAsyncLocalValueMap multi = new MultiElementAsyncLocalValueMap(keys.length);
						System.arraycopy(keys, 0, multi.keys, 0, keys.length);
						System.arraycopy(values, 0, multi.values, 0, values.length);
						multi.keys[i] = key;
						multi.values[i] = value;
						return multi;
					} else if (keys.length == 4) {
						// The value is null, and we only have four elements, one of which we're removing,
						// so downgrade to a three-element map, without the matching element.
						return i == 0 ? new ThreeElementAsyncLocalValueMap(keys[1], values[1], keys[2], values[2], keys[3], values[3])
							: i == 1 ? new ThreeElementAsyncLocalValueMap(keys[0], values[0], keys[2], values[2], keys[3], values[3])
								: i == 2 ? new ThreeElementAsyncLocalValueMap(keys[0], values[0], keys[1], values[1], keys[3], values[3])
									: new ThreeElementAsyncLocalValueMap(keys[0], values[0], keys[1], values[1], keys[2], values[2]);
					} else {
						// The value is null, and we have enough elements remaining to warrant a multi map.
						// Create a new one and copy all of the elements from this one, except the one to be removed.
						MultiElementAsyncLocalValueMap multi = new MultiElementAsyncLocalValueMap(keys.length - 1);
						if (i != 0) {
							System.arraycopy(keys, 0, multi.keys, 0, i);
							System.arraycopy(values, 0, multi.values, 0, i);
						}

						if (i != keys.length - 1) {
							System.arraycopy(keys, i + 1, multi.keys, i, keys.length - i - 1);
							System.arraycopy(values, i + 1, multi.values, i, values.length - i - 1);
						}

						return multi;
					}
				}
			}

			// The key does not already exist in this map.
			// If the value is null, then we can simply return this same map, as there's nothing to add or remove.
			if (value == null) {
				return this;
			}

			// We need to create a new map that has the additional key/value pair.
			// If with the addition we can still fit in a multi map, create one.
			if (keys.length < MAX_MULTI_ELEMENTS) {
				MultiElementAsyncLocalValueMap multi = new MultiElementAsyncLocalValueMap(keys.length + 1);
				System.arraycopy(keys, 0, multi.keys, 0, keys.length);
				System.arraycopy(values, 0, multi.values, 0, values.length);
				multi.keys[keys.length] = key;
				multi.values[values.length] = value;
				return multi;
			}

			// Otherwise, upgrade to a many map.
			ManyElementAsyncLocalValueMap many = new ManyElementAsyncLocalValueMap(MAX_MULTI_ELEMENTS + 1);
			for (int i = 0; i < keys.length; i++) {
				many.unsafeStore(keys[i], values[i]);
			}

			many.unsafeStore(key, value);
			return many;
		}

		@Override
		public <T> T get(AsyncLocal<T> key) {
			for (int i = 0; i < keys.length; i++) {
				if (keys[i] == key) {
					@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
					T result = (T)values[i];
					return result;
				}
			}

			return null;
		}
	}

	// Instance with any number of key/value pairs.
	private static final class ManyElementAsyncLocalValueMap extends AsyncLocalValueMap {

		private final Map<AsyncLocal<?>, Object> values;

		public ManyElementAsyncLocalValueMap(int capacity) {
			values = new HashMap<>(capacity);
		}

		@Override
		public <T> AsyncLocalValueMap put(AsyncLocal<T> key, T value) {
			int count = values.size();
			boolean containsKey = values.containsKey(key);

			// If the value being set exists, create a new many map, copy all of the elements from this one,
			// and then store the new key/value pair into it.  This is the most common case.
			if (value != null) {
				ManyElementAsyncLocalValueMap map = new ManyElementAsyncLocalValueMap(count + (containsKey ? 0 : 1));
				for (Map.Entry<AsyncLocal<?>, Object> pair : values.entrySet()) {
					// This cast is safe four our use in copying the map
					@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
					AsyncLocal<Object> k = (AsyncLocal<Object>)pair.getKey();
					map.unsafeStore(k, pair.getValue());
				}

				map.unsafeStore(key, value);
				return map;
			}

			// Otherwise, the value is null, which means null is being stored into an AsyncLocal.Value.
			// Since there's no observable difference at the API level between storing null and the key
			// not existing at all, we can downgrade to a smaller map rather than storing null.
			// If the key is contained in this map, we're going to create a new map that's one pair smaller.
			if (containsKey) {
				// If the new count would be within range of a multi map instead of a many map,
				// downgrade to the many map, which uses less memory and is faster to access.
				// Otherwise, just create a new many map that's missing this key.
				if (count == MultiElementAsyncLocalValueMap.MAX_MULTI_ELEMENTS + 1) {
					MultiElementAsyncLocalValueMap multi = new MultiElementAsyncLocalValueMap(MultiElementAsyncLocalValueMap.MAX_MULTI_ELEMENTS);
					int index = 0;
					for (Map.Entry<AsyncLocal<?>, Object> pair : values.entrySet()) {
						if (key != pair.getKey()) {
							// This cast is safe four our use in copying the map
							@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
							AsyncLocal<Object> k = (AsyncLocal<Object>)pair.getKey();

							multi.unsafeStore(index++, k, pair.getValue());
						}
					}

					assert index == MultiElementAsyncLocalValueMap.MAX_MULTI_ELEMENTS;
					return multi;
				} else {
					ManyElementAsyncLocalValueMap map = new ManyElementAsyncLocalValueMap(count - 1);
					for (Map.Entry<AsyncLocal<?>, Object> pair : values.entrySet()) {
						if (key != pair.getKey()) {
							// This cast is safe four our use in copying the map
							@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
							AsyncLocal<Object> k = (AsyncLocal<Object>)pair.getKey();

							map.unsafeStore(k, pair.getValue());
						}
					}

					assert map.values.size() == count - 1;
					return map;
				}
			}

			// We were storing null, but the key wasn't in the map, so there's nothing to change.
			// Just return this instance.
			return this;
		}

		@Override
		public <T> T get(AsyncLocal<T> key) {
			@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
			T result = (T)values.get(key);
			return result;
		}

		<T> void unsafeStore(AsyncLocal<T> key, T value) {
			values.put(key, value);
		}
	}

}
