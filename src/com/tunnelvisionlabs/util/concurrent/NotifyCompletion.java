// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;

/**
 * Represents an operation that schedules continuations when it completes.
 */
public interface NotifyCompletion {

	/**
	 * Schedules the continuation action that's invoked when the instance completes.
	 *
	 * @param continuation The action to invoke when the operation completes.
	 */
	void onCompleted(@NotNull Runnable continuation);

}
