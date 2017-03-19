// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

enum Suppressions {
	;

	public static final String UNCHECKED_SAFE = "unchecked";

	/**
	 * A resource is not used within a {@code try}-with-resources statement because it's only used for scoping a
	 * resource for cleanup at the appropriate time.
	 */
	public static final String TRY_SCOPE = "try";
}
