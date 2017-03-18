// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.HashMap;
import java.util.Map;

class CallContext {
	private static final ThreadLocal<CallContext> CURRENT_CONTEXT = ThreadLocal.withInitial(CallContext::new);

	private final Object lock = new Object();
	private boolean needCopy;
	private Map<String, Object> data;

	private CallContext() {
	}

	private CallContext(Map<String, Object> data) {
		this.needCopy = data != null;
		this.data = data;
	}

	@NotNull
	public static CallContext getCurrent() {
		return CURRENT_CONTEXT.get();
	}

	static void setCallContext(@NotNull CallContext callContext) {
		CURRENT_CONTEXT.set(callContext);
	}

	public static void freeNamedDataSlot(String name) {
		CallContext context = getCurrent();
		synchronized (context.lock) {
			if (context.data == null) {
				return;
			}

			if (!context.data.containsKey(name)) {
				return;
			}

			if (context.needCopy) {
				context.needCopy = false;
				context.data = new HashMap<>(context.data);
			}

			context.data.remove(name);
		}
	}

	public static Object getData(String name) {
		CallContext context = getCurrent();
		synchronized (context.lock) {
			if (context.data == null) {
				return null;
			}

			return context.data.get(name);
		}
	}

	public static void setData(String name, Object value) {
		CallContext context = getCurrent();
		synchronized (context.lock) {
			if (context.data == null) {
				context.data = new HashMap<>();
			} else if (context.needCopy) {
				context.needCopy = false;
				context.data = new HashMap<>(context.data);
			}

			context.data.put(name, value);
		}
	}

	@NotNull
	public CallContext createCopy() {
		synchronized (lock) {
			needCopy = true;
			return new CallContext(data);
		}
	}
}
