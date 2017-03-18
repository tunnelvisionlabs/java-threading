// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.concurrent.CompletableFuture;

/**
 * A flavor of {@code ManualResetEvent} that can be asynchronously awaited on.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncManualResetEvent implements Awaitable<Void> {
	/**
	 * Whether the future should allow executing continuations synchronously.
	 */
	private final boolean allowInliningAwaiters;

	/**
	 * The object to lock when accessing fields.
	 */
	private final Object syncObject = new Object();

	/**
	 * The source of the future to return from {@link waitAsync}.
	 *
	 * <p>This should not need the {@code volatile} modifier because it is always accessed within a lock.</p>
	 */
	private CompletableFutureWithoutInlining<Void> future;

	private CompletableFuture<Void> priorityFuture;
	private CompletableFuture<Void> secondaryFuture;

	/**
	 * A flag indicating whether the event is signaled. When this is set to {@code true}, it's possible that
	 * {@link #future}.isDone() is still {@code false} if the completion has been scheduled asynchronously. Thus, this
	 * field should be the definitive answer as to whether the event is signaled because it is synchronously updated.
	 *
	 * <p>This should not need the {@code volatile} modifier because it is always accessed within a lock.</p>
	 */
	private boolean isSet;

	public AsyncManualResetEvent() {
		this(false, false);
	}

	public AsyncManualResetEvent(boolean initialState) {
		this(initialState, false);
	}

	/**
	 * Constructs a new instance of the {@link AsyncManualResetEvent} class.
	 *
	 * @param initialState A value indicating whether the event should be initially signaled.
	 * @param allowInliningAwaiters A value indicating whether to allow {@link #waitAsync()} callers' continuations
	 * to execute on the thread that calls {@link #setAsync()} before the call returns. {@link #setAsync()} callers
	 * should not hold private locks if this value is {@code true} to avoid deadlocks. When {@code false}, the
	 * future returned from {@link #waitAsync()} may not have fully transitioned to its completed state by the time
	 * {@link #setAsync()} returns to its caller.
	 */
	public AsyncManualResetEvent(boolean initialState, boolean allowInliningAwaiters) {
		this.allowInliningAwaiters = allowInliningAwaiters;

		this.future = createFuture();
		this.isSet = initialState;
		if (initialState) {
			// Complete the task immediately.
			future.complete(null);
		}

		StrongBox<CompletableFuture<Void>> secondaryHandlerBox = new StrongBox<>();
		this.priorityFuture = Futures.createPriorityHandler(this.future, secondaryHandlerBox);
		this.secondaryFuture = secondaryHandlerBox.value;
	}

	/**
	 * Gets a value indicating whether the event is currently in a signaled state.
	 */
	public final boolean isSet() {
		synchronized (this.syncObject) {
			return this.isSet;
		}
	}

	/**
	 * Returns a future that will be completed when this event is set.
	 */
	@NotNull
	public final CompletableFuture<Void> waitAsync() {
		synchronized (this.syncObject) {
			return Futures.nonCancellationPropagating(priorityFuture);
		}
	}

	/**
	 * Sets this event to unblock callers of {@link #waitAsync()}.
	 *
	 * @return A future that completes when the signal has been set.
	 * @deprecated Use {@link #set()} instead.
	 */
	@Deprecated
	final CompletableFuture<Void> setAsync() {
		CompletableFutureWithoutInlining<Void> localFuture;
		CompletableFuture<Void> secondaryFuture;
		boolean transitionRequired = false;
		synchronized (this.syncObject) {
			transitionRequired = !this.isSet;
			localFuture = this.future;
			secondaryFuture = this.secondaryFuture;
			this.isSet = true;
		}

		if (transitionRequired) {
			localFuture.trySetResultToNull();
		}

		return secondaryFuture;
	}

	/**
	 * Sets this event to unblock callers of {@link #waitAsync()}.
	 */
	public final void set() {
		this.setAsync();
	}

	/**
	 * Resets this event to a state that will block callers of {@link #waitAsync()}.
	 */
	public final void reset() {
		synchronized (this.syncObject) {
			if (this.isSet) {
				this.future = this.createFuture();

				StrongBox<CompletableFuture<Void>> secondaryHandlerBox = new StrongBox<>();
				this.priorityFuture = Futures.createPriorityHandler(this.future, secondaryHandlerBox);
				this.secondaryFuture = secondaryHandlerBox.value;

				this.isSet = false;
			}
		}
	}

	/**
	 * Sets and immediately resets this event, allowing all current waiters to unblock.
	 *
	 * @return A future that completes when the signal has been set.
	 * @deprecated Use {@link #pulseAll()} instead.
	 */
	@NotNull
	@Deprecated
	final CompletableFuture<Void> pulseAllAsync() {
		CompletableFutureWithoutInlining<Void> localFuture;
		CompletableFuture<Void> secondaryFuture;
		synchronized (this.syncObject) {
			// Atomically replace the future with a new, uncompleted future
			// while capturing the previous one so we can complete it.
			// This ensures that we don't leave a gap in time where waitAsync() will
			// continue to return completed futures due to a pulse method which should
			// execute instantaneously.
			localFuture = this.future;
			secondaryFuture = this.secondaryFuture;

			this.future = this.createFuture();

			StrongBox<CompletableFuture<Void>> secondaryHandlerBox = new StrongBox<>();
			this.priorityFuture = Futures.createPriorityHandler(this.future, secondaryHandlerBox);
			this.secondaryFuture = secondaryHandlerBox.value;

			this.isSet = false;
		}

		localFuture.trySetResultToNull();
		return secondaryFuture;
	}

	/**
	 * Sets and immediately resets this event, allowing all current waiters to unblock.
	 */
	public final void pulseAll() {
		pulseAllAsync();
	}

	/**
	 * Gets an awaiter that completes when this event is signaled.
	 */
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1024:UsePropertiesWhereAppropriate")]
//        [EditorBrowsable(EditorBrowsableState.Never)]
	@NotNull
	@Override
	public final FutureAwaitable<Void>.FutureAwaiter getAwaiter() {
		return new FutureAwaitable<>(waitAsync(), true).getAwaiter();
	}

	/**
	 * Creates a new {@link CompletableFuture} to represent an unset event.
	 */
	@NotNull
	private CompletableFutureWithoutInlining<Void> createFuture() {
		return new CompletableFutureWithoutInlining<>(this.allowInliningAwaiters);
	}
}
