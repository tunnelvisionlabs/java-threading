// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class CancellationTokenRegistration implements Disposable {
	private static final CancellationTokenRegistration NONE = new CancellationTokenRegistration(null, null);

	private final WeakReference<CancellationTokenSource> weakSource;
	private final AtomicReference<Runnable> runnable;

	private CancellationTokenRegistration(CancellationTokenSource source, Runnable runnable) {
		this.weakSource = new WeakReference<>(source);
		this.runnable = new AtomicReference<>(runnable);
	}

	static <T> CancellationTokenRegistration create(CancellationTokenSource source, Consumer<T> callback, T state, boolean useSynchronizationContext) {
		Runnable runnable = null;
		if (useSynchronizationContext) {
			SynchronizationContext synchronizationContext = SynchronizationContext.getCurrent();
			if (synchronizationContext != null && synchronizationContext.getClass() != SynchronizationContext.class) {
				runnable = ExecutionContext.wrap(() -> {
					synchronizationContext.send(callback, state);
				});
			}
		}

		if (runnable == null) {
			runnable = ExecutionContext.wrap(() -> callback.accept(state));
		}

		return new CancellationTokenRegistration(source, runnable);
	}

	@NotNull
	static CancellationTokenRegistration none() {
		return NONE;
	}

	void tryExecute() {
		Runnable currentRunnable = runnable.getAndSet(null);
		if (currentRunnable == null) {
			return;
		}

		weakSource.clear();
		currentRunnable.run();
	}

	@Override
	public void close() {
		Runnable currentRunnable = runnable.getAndSet(null);
		if (currentRunnable == null) {
			return;
		}

		CancellationTokenSource source = weakSource.get();
		if (source == null) {
			return;
		}

		source.unregister(this);
	}
}
