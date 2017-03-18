// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;

public interface Awaitable<T> {
	@NotNull
	Awaiter<T> getAwaiter();
}
