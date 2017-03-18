// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

class SynchronizationContext implements Executor {
	private static final ThreadLocal<SynchronizationContext> CURRENT_CONTEXT = new ThreadLocal<>();

	private boolean waitNotificationRequired;

	@Nullable
	public static SynchronizationContext getCurrent() {
		return CURRENT_CONTEXT.get();
	}

	public static void setSynchronizationContext(@Nullable SynchronizationContext synchronizationContext) {
		CURRENT_CONTEXT.set(synchronizationContext);
	}

	@NotNull
	public SynchronizationContext createCopy() {
		throw new UnsupportedOperationException("Not implemented");
	}

	public final boolean isWaitNotificationRequired() {
		return waitNotificationRequired;
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
		waitNotificationRequired = true;
	}

	@Override
	public void execute(@NotNull Runnable command) {
		post(state -> command.run(), null);
	}
}
