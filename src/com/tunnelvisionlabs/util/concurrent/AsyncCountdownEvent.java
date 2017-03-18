// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An asynchronous style countdown event.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncCountdownEvent {
	/**
	 * The manual reset event we use to signal all awaiters.
	 */
	private final AsyncManualResetEvent manualEvent;

	/**
	 * The remaining number of signals required before we can unblock waiters.
	 */
	private final AtomicInteger remainingCount = new AtomicInteger();

	/**
	 * Constructs a new instance of the {@link AsyncCountdownEvent} class.
	 *
	 * @param initialCount The number of signals required to unblock awaiters.
	 */
	public AsyncCountdownEvent(int initialCount) {
		Requires.range(initialCount >= 0, "initialCount");
		this.manualEvent = new AsyncManualResetEvent(initialCount == 0);
		this.remainingCount.set(initialCount);
	}

	/**
	 * Returns a future that executes the continuation when the countdown reaches zero.
	 *
	 * @return A future.
	 */
	@NotNull
	public final CompletableFuture<Void> waitAsync() {
		return this.manualEvent.waitAsync();
	}

	/**
	 * Decrements the counter by one.
	 *
	 * @return A future that completes when the signal has been set if this call causes the count to reach zero. If the
	 * count is not zero, a completed future is returned.
	 *
	 * @deprecated Use {@link #signal()} instead.
	 */
	@Deprecated
	final CompletableFuture<Void> signalAsync() {
		return Async.runAsync(() -> {
			int newCount = remainingCount.decrementAndGet();
			if (newCount == 0) {
				return Async.awaitAsync(Async.configureAwait(manualEvent.setAsync(), false));
			} else if (newCount < 0) {
				throw new IllegalStateException();
			} else {
				return Futures.completedNull();
			}
		});
	}

	/**
	 * Decrements the counter by one.
	 */
	public final void signal() {
		int newCount = remainingCount.decrementAndGet();
		if (newCount == 0) {
			this.manualEvent.set();
		} else if (newCount < 0) {
			throw new IllegalStateException();
		}
	}

	/**
	 * Decrements the counter by one and returns a future that executes the continuation when the countdown reaches
	 * zero.
	 *
	 * @return A future.
	 */
	public final CompletableFuture<Void> signalAndWaitAsync() {
		try {
			this.signal();
			return this.waitAsync();
		} catch (Throwable ex) {
			return Futures.fromException(ex);
		}
	}
}
