// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

interface IAsyncLocalValueMap {
	Object get(IAsyncLocal key);
	IAsyncLocalValueMap put(IAsyncLocal key, Object value);
}
