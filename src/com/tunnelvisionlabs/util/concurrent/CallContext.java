// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

class CallContext {
	private static final ThreadLocal<CallContext> CURRENT_CONTEXT = ThreadLocal.withInitial(() -> new CallContext());

	private CallContext() {
	}

	@NotNull
	public static CallContext getCurrent() {
		return CURRENT_CONTEXT.get();
	}

	static void setCallContext(@NotNull CallContext callContext) {
		CURRENT_CONTEXT.set(callContext);
	}
}
