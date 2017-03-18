// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

/**
 * Specifies flags that control optional behavior for the creation and execution of futures.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public enum JoinableFutureCreationOption {
	/**
	 * Specifies that a future will be a long-running operation. It provides a hint to the {@link JoinableFutureContext}
	 * that hang report should not be fired, when the main thread future is blocked on it.
	 */
	LONG_RUNNING,
}
