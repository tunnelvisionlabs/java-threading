// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.function.Consumer;

class SynchronizationContext {
	private static final ThreadLocal<SynchronizationContext> CURRENT_CONTEXT = new ThreadLocal<>();

	@Nullable
	public static SynchronizationContext getCurrent() {
		return CURRENT_CONTEXT.get();
	}

	public static void setSynchronizationContext(@Nullable SynchronizationContext synchronizationContext) {
		throw new UnsupportedOperationException("Not implemented");
	}

	@NotNull
	public SynchronizationContext createCopy() {
		throw new UnsupportedOperationException("Not implemented");
	}

	public final boolean isWaitNotificationRequired() {
		throw new UnsupportedOperationException("Not implemented");
	}

	public void operationStarted() {
	}

	public void operationCompleted() {
	}

	public <T> void post(@NotNull Consumer<T> callback, T state) {
		throw new UnsupportedOperationException("Not implemented");
	}

	public <T> void send(@NotNull Consumer<T> callback, T state) {
		throw new UnsupportedOperationException("Not implemented");
	}

	protected final void setWaitNotificationRequired() {
		throw new UnsupportedOperationException("Not implemented");
	}
}
