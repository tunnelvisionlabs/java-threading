// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

public interface Awaiter<T> extends NotifyCompletion {

	boolean isDone();

	T getResult();

}
