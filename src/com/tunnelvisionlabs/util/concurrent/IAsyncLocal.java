// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

public interface IAsyncLocal {
	void onValueChanged(Object previousValue, Object currentValue, boolean contextChanged);
}
