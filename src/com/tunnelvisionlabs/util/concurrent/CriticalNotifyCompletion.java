// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;

/**
 * Represents an awaiter that schedules continuations when an await operation completes.
 */
public interface CriticalNotifyCompletion extends NotifyCompletion {

	/**
	 * Schedules the continuation action that's invoked when the instance completes.
	 *
	 * <p>Unlike {@link #onCompleted}, {@link #unsafeOnCompleted} doesn't have to propagate {@link ExecutionContext}
	 * information.</p>
	 *
	 * @param continuation The action to invoke when the operation completes.
	 */
	void unsafeOnCompleted(@NotNull Runnable continuation);
}
