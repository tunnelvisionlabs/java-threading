// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public class CancellationTokenSource implements Disposable {
	private final CancellationToken token = new CancellationToken(this);
	private final List<CancellationTokenRegistration> registrations = new ArrayList<>();

	private boolean cancellationRequested;
	private boolean closed;

	public CancellationTokenSource() {
	}

	public CancellationTokenSource(Duration duration) {
		cancelAfter(duration);
	}

	public static CancellationTokenSource createLinkedTokenSource(CancellationToken token1, CancellationToken token2) {
		throw new UnsupportedOperationException("Not implemented");
	}

	public static CancellationTokenSource createLinkedTokenSource(CancellationToken... tokens) {
		throw new UnsupportedOperationException("Not implemented");
	}

	public final boolean isCancellationRequested() {
		return cancellationRequested;
	}

	@NotNull
	public final CancellationToken getToken() {
		if (isClosed()) {
			throw new IllegalStateException("The source is disposed");
		}

		return token;
	}

	final boolean isClosed() {
		return closed;
	}

	public final void cancel() {
		cancel(false);
	}

	public final void cancel(boolean throwOnFirstException) {
		List<CancellationTokenRegistration> registrationsToInvoke;
		synchronized (registrations) {
			if (isClosed()) {
				throw new IllegalStateException("The source is disposed");
			}

			if (isCancellationRequested()) {
				return;
			}

			cancellationRequested = true;

			if (registrations.isEmpty()) {
				return;
			}

			registrationsToInvoke = new ArrayList<>(registrations);
			registrations.clear();
		}

		List<Throwable> exceptions = null;

		for (CancellationTokenRegistration registration : registrationsToInvoke) {
			try {
				registration.tryExecute();
			} catch (Throwable ex) {
				if (throwOnFirstException) {
					throw new CompletionException(ex);
				}

				if (exceptions == null) {
					exceptions = new ArrayList<>();
				}

				exceptions.add(ex);
			}
		}

		if (exceptions != null) {
			throw new AggregateException(exceptions);
		}
	}

	public final void cancelAfter(Duration duration) {
		throw new UnsupportedOperationException("Not implemented");
	}

	@Override
	public void close() {
		synchronized (registrations) {
			registrations.clear();
			closed = true;
		}
	}

	@NotNull
	final <T> CancellationTokenRegistration register(Consumer<T> callback, T state, boolean useSynchronizationContext) {
		CancellationTokenRegistration registration = CancellationTokenRegistration.create(this, callback, state, useSynchronizationContext);
		synchronized (registrations) {
			if (isClosed()) {
				throw new IllegalStateException("The source is disposed");
			}

			if (!isCancellationRequested()) {
				registrations.add(registration);
				return registration;
			}
		}

		// If we get here, it means the source was already cancelled. Execute the callback directly on the current thread.
		callback.accept(state);
		return CancellationTokenRegistration.none();
	}

	final void unregister(CancellationTokenRegistration registration) {
		synchronized (registrations) {
			// Remove if possible, otherwise ignore
			registrations.remove(registration);
		}
	}
}
