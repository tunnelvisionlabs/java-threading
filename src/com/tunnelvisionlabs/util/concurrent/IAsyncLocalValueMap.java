// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

interface IAsyncLocalValueMap {
	<T> T get(AsyncLocal<T> key);
	<T> IAsyncLocalValueMap put(AsyncLocal<T> key, T value);
}
