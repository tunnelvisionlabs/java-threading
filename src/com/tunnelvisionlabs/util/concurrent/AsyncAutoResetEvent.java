// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * An asynchronous implementation of an {@code AutoResetEvent}.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncAutoResetEvent {
	/**
	 * A queue of folks awaiting signals.
	 */
	private final Deque<WaiterCompletableFuture> signalAwaiters = new ArrayDeque<>();

	/**
	 * Whether to complete the future synchronously in the {@link #set()} method, as opposed to asynchronously.
	 */
	private final boolean allowInliningAwaiters;

	/**
	 * A value indicating whether this event is already in a signaled state.
	 *
	 * <p>This should not need the {@code volatile} modifier because it is always accessed within a lock.</p>
	 */
	private boolean signaled;

	/**
	 * Constructs a new instance of the {@link AsyncAutoResetEvent} class that does not inline awaiters.
	 */
	public AsyncAutoResetEvent() {
		this(false);
	}

	/**
	 * Constructs a new instance of the {@link AsyncAutoResetEvent} class.
	 *
	 * @param allowInliningAwaiters A value indicating whether to complete the future synchronously in the {@link #set}
	 * method, as opposed to asynchronously. {@code false} better simulates the behavior of the {@code AutoResetEvent}
	 * class, but {@code true} can result in slightly better performance.
	 */
	public AsyncAutoResetEvent(boolean allowInliningAwaiters) {
		this.allowInliningAwaiters = allowInliningAwaiters;
	}

	/**
	 * Returns a future that may be used to asynchronously acquire the next signal.
	 *
	 * @return A future representing the asynchronous operation.
	 */
	@NotNull
	public final CompletableFuture<Void> waitAsync() {
		synchronized (this.signalAwaiters) {
			if (this.signaled) {
				this.signaled = false;
				return Futures.completedNull();
			} else {
				WaiterCompletableFuture waiter = new WaiterCompletableFuture(this.allowInliningAwaiters);
				this.signalAwaiters.add(waiter);
				return waiter;
			}
		}
	}

	/**
	 * Sets the signal if it has not already been set, allowing one continuation to handle the signal if one is already
	 * waiting.
	 */
	public final void set() {
		WaiterCompletableFuture toRelease = null;
		synchronized (this.signalAwaiters) {
			if (!this.signalAwaiters.isEmpty()) {
				toRelease = this.signalAwaiters.poll();
			} else if (!this.signaled) {
				this.signaled = true;
			}
		}

		if (toRelease != null) {
			toRelease.trySetResultToNull();
		}
	}

	/**
	 * Tracks someone waiting for a signal from the event.
	 */
	private class WaiterCompletableFuture extends CompletableFutureWithoutInlining<Void> {

		/**
		 * Constructs a new instance of the {@link WaiterCompletableFuture} class.
		 *
		 * @param owner The event that is initializing this value
		 * @param allowInliningContinuations {@code true} to allow continuations to be inlined upon the completer's
		 * call stack.
		 */
		public WaiterCompletableFuture(boolean allowInliningContinuations) {
			super(allowInliningContinuations);
		}

		@Override
		protected boolean canCancel() {
			boolean removed;
			synchronized (signalAwaiters) {
				removed = signalAwaiters.remove(this);
			}

			// We only cancel the future if we removed it from the queue.
			// If it wasn't in the queue, it has already been signaled.
			return removed;
		}
	}
}
