// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

/**
 * An {@link AutoCloseable} object which does not throw an exception from {@link #close()}.
 */
public interface Disposable extends AutoCloseable {
	/**
	 * {@inheritDoc}
	 */
	@Override
	void close();
}
