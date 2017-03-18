// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

class SynchronizationContext implements Executor {
	private boolean waitNotificationRequired;

	@Nullable
	public static SynchronizationContext getCurrent() {
		return ThreadProperties.getSynchronizationContext(Thread.currentThread());
	}

	public static void setSynchronizationContext(@Nullable SynchronizationContext synchronizationContext) {
		ThreadProperties.setSynchronizationContext(Thread.currentThread(), synchronizationContext);
	}

	@NotNull
	public SynchronizationContext createCopy() {
		return new SynchronizationContext();
	}

	public final boolean isWaitNotificationRequired() {
		return waitNotificationRequired;
	}

	public void operationStarted() {
	}

	public void operationCompleted() {
	}

	public <T> void post(@NotNull Consumer<T> callback, T state) {
		Futures.runAsync(() -> callback.accept(state));
	}

	public <T> void send(@NotNull Consumer<T> callback, T state) {
		callback.accept(state);
	}

	protected final void setWaitNotificationRequired() {
		waitNotificationRequired = true;
	}

	@Override
	public void execute(@NotNull Runnable command) {
		post(Runnable::run, command);
	}
}
