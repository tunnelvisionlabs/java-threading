// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

/**
 * Holds a reference to a value.
 *
 * @param <T> The type of the value that the {@link StrongBox} references.
 */
final class StrongBox<T> {
	/**
	 * Represents the value that the {@link StrongBox} references.
	 */
	public T value;

	/**
	 * Constructs a new instance of the {@link StrongBox} class which can hold a reference to a value.
	 */
	public StrongBox() {
	}

	/**
	 * Constructs a new instance of the {@link StrongBox} class with the specified initial value.
	 */
	public StrongBox(T value) {
		this.value = value;
	}
}
