// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import java.lang.ref.WeakReference;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public final class CancellationToken {
	private static final CancellationToken NONE = new CancellationToken();

	@Nullable
	private final WeakReference<CancellationTokenSource> weakSource;

	private CancellationToken() {
		this.weakSource = new WeakReference<>(null);
	}

	CancellationToken(CancellationTokenSource source) {
		this.weakSource = new WeakReference<>(source);
	}

	@NotNull
	public static CancellationToken none() {
		return NONE;
	}

	public boolean canBeCancelled() {
		CancellationTokenSource source = weakSource.get();
		return source != null && !source.isClosed();
	}

	public boolean isCancellationRequested() {
		CancellationTokenSource source = weakSource.get();
		return source != null && source.isCancellationRequested();
	}

	public final void throwIfCancellationRequested() throws CancellationException {
		if (isCancellationRequested()) {
			throw new CancellationException();
		}
	}

	public final CancellationTokenRegistration register(Runnable runnable) {
		return register(runnable, false);
	}

	public final CancellationTokenRegistration register(Runnable runnable, boolean useSynchronizationContext) {
		return register(Runnable::run, runnable, useSynchronizationContext);
	}

	public final <T> CancellationTokenRegistration register(Consumer<T> callback, T state) {
		return register(callback, state, false);
	}

	public final <T> CancellationTokenRegistration register(Consumer<T> callback, T state, boolean useSynchronizationContext) {
		CancellationTokenSource source = weakSource.get();
		if (source == null) {
			return CancellationTokenRegistration.none();
		}

		return source.register(callback, state, useSynchronizationContext);
	}
}
