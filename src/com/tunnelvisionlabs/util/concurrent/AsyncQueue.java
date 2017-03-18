// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * A thread-safe, asynchronously pollable queue.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 *
 * @param <T> The type of values kept by the queue.
 */
public class AsyncQueue<T> {
	/**
	 * The object to lock when reading/writing the internal data structures.
	 */
	private final Object syncObject = new Object();

	/**
	 * The futures wanting to poll elements from the queue. Lazily constructed.
	 */
	private Queue<CompletableFuture<T>> pollingFutures;

	/**
	 * The future returned by {@link #getFuture()}. Lazily constructed.
	 *
	 * <p>Volatile to allow the check-lock-check pattern in {@link #getFuture()} to be reliable, in the event that
	 * within the lock, one thread initializes the value and assigns the field and the weak memory model allows the
	 * assignment prior to the initialization. Another thread outside the lock might observe the non-null field and
	 * start accessing the field before it is actually initialized. Volatile prevents CPU reordering of commands around
	 * the assignment (or read) of this field.</p>
	 */
	private volatile CompletableFuture<Void> future;

	/**
	 * The internal queue of elements. Lazily constructed.
	 */
	private Deque<T> queueElements;

	/**
	 * A value indicating whether {@link #complete()} has been called.
	 */
	private boolean completeSignaled;

	/**
	 * A flag indicating whether {@link #onCompleted()} has been invoked.
	 */
	private boolean onCompletedInvoked;

	/**
	 * Constructs a new instance of the {@link AsyncQueue} class.
	 */
	public AsyncQueue() {
	}

	/**
	 * Gets a value indicating whether the queue is currently empty.
	 */
	public final boolean isEmpty() {
		return this.size() == 0;
	}

	/**
	 * Gets the number of elements currently in the queue.
	 */
	public final int size() {
		synchronized (syncObject) {
			return this.queueElements != null ? this.queueElements.size() : 0;
		}
	}

	/**
	 * Gets a value indicating whether the queue has completed.
	 *
	 * <p>
	 * This is arguably redundant with {@link #getFuture()}.isDone(), but this property won't cause the lazy
	 * instantiation of the {@link CompletableFuture} that {@link #getFuture()} may if there is no other reason for the
	 * {@link CompletableFuture} to exist.</p>
	 */
	public final boolean isCompleted() {
		synchronized (syncObject) {
			return this.completeSignaled && this.isEmpty();
		}
	}

	/**
	 * Gets a future that completes when {@link #complete()} is called.
	 */
	public CompletableFuture<Void> getFuture() {
		if (future == null) {
			synchronized (syncObject) {
				if (future == null) {
					if (isCompleted()) {
						return Futures.completedNull();
					} else {
						future = new CompletableFuture<>();
					}
				}
			}
		}

		return future;
	}

	/**
	 * Gets the synchronization object used by this queue.
	 */
	protected final Object getSyncRoot() {
		return this.syncObject;
	}

	/**
	 * Gets the initial capacity for the queue.
	 */
	protected int getInitialCapacity() {
		return 4;
	}

	/**
	 * Signals that no further elements will be added.
	 */
	public final void complete() {
		synchronized (syncObject) {
			completeSignaled = true;
		}

		completeIfNecessary();
	}

	/**
	 * Adds an element to the tail of the queue.
	 *
	 * @param value The value to add.
	 */
	public final void add(@NotNull T value) {
		if (!this.tryAdd(value)) {
			throw new IllegalStateException("InvalidAfterCompleted");
		}
	}

	/**
	 * Adds an element to the tail of the queue if it has not yet completed.
	 *
	 * @param value The value to add.
	 * @return {@code true} if the value was added to the queue; {@code false} if the queue is already completed.
	 */
	public final boolean tryAdd(@NotNull T value) {
		Requires.notNull(value, "value");

		CompletableFuture<T> poller = null;
		synchronized (syncObject) {
			if (completeSignaled) {
				return false;
			}

			if (pollingFutures != null) {
				while (!pollingFutures.isEmpty()) {
					poller = pollingFutures.poll();
					if (poller != null && poller.isDone()) {
						// Skip this one
						poller = null;
						continue;
					}

					break;
				}
			}

			if (poller == null) {
				// There were no waiting pollers, so actually add this element to our queue.
				if (queueElements == null) {
					queueElements = new ArrayDeque<>(this.getInitialCapacity());
				}

				queueElements.add(value);
			}
		}

		// important because we'll transition a task to complete.
		assert !Thread.holdsLock(syncObject);

		// We only transition this future to complete outside of our lock so
		// we don't accidentally inline continuations inside our lock.
		if (poller != null) {
			// There was already someone waiting for an element to process, so
			// immediately allow them to begin work and skip our internal queue.
			if (!poller.complete(value)) {
				// Retry the add in the event of a race.
				return tryAdd(value);
			}
		}

		onAdded(value, poller != null);

		return true;
	}

	/**
	 * Gets the value at the head of the queue without removing it from the queue, if it is non-empty.
	 *
	 * @return The value at the head of the queue; or {@code null} if the queue is empty.
	 */
	@Nullable
	public final T peek() {
		synchronized (syncObject) {
			if (queueElements != null) {
				return queueElements.peek();
			} else {
				return null;
			}
		}
	}

//        /// <summary>
//        /// Gets the value at the head of the queue without removing it from the queue.
//        /// </summary>
//        /// <exception cref="InvalidOperationException">Thrown if the queue is empty.</exception>
//        public T Peek()
//        {
//            T value;
//            if (!this.TryPeek(out value))
//            {
//                Verify.FailOperation(Strings.QueueEmpty);
//            }
//
//            return value;
//        }

	/**
	 * Gets a future whose result is the element at the head of the queue.
	 *
	 * @return A future whose result is the head element.
	 */
	public final CompletableFuture<T> pollAsync() {
		return pollAsync(CancellationToken.none());
	}

	/**
	 * Gets a future whose result is the element at the head of the queue.
	 *
	 * @return A future whose result is the head element.
	 */
	public final CompletableFuture<T> pollAsync(@NotNull CancellationToken cancellationToken) {
		CompletableFuture<T> completableFuture = new CompletableFuture<>();
		if (cancellationToken.canBeCancelled()) {
			CancellationTokenRegistration registration = cancellationToken.register(() -> completableFuture.cancel(false));
			completableFuture.whenComplete((result, exception) -> registration.close());
		}

		synchronized (syncObject) {
			T value = pollInternal(null);
			if (value != null) {
				completableFuture.complete(value);
			} else {
				if (pollingFutures == null) {
					pollingFutures = new ArrayDeque<>();
				}

				pollingFutures.add(completableFuture);
			}
		}

		completeIfNecessary();
		return completableFuture;
	}

	/**
	 * Immediately polls the element from the head of the queue if one is available, otherwise returns {@code null}.
	 *
	 * @return The element from the head of the queue; or {@code null} if the queue is empty.
	 */
	@Nullable
	public final T poll() {
		T result = pollInternal(null);
		completeIfNecessary();
		return result;
	}

	/**
	 * Returns a copy of this queue as a list.
	 */
	@NotNull
	final List<T> toList() {
		synchronized (syncObject) {
			return new ArrayList<>(queueElements);
		}
	}

	/**
	 * Immediately polls the element from the head of the queue if one is available that satisfies the specified check;
	 * otherwise returns {@code null}.
	 *
	 * @param valueCheck The test on the head element that must succeed to poll.
	 * @return The element from the head of the queue; or {@code null} if the queue is empty or the first element does
	 * not match the predicate.
	 */
	@Nullable
	protected final T poll(@NotNull Predicate<? super T> valueCheck) {
		Requires.notNull(valueCheck, "valueCheck");

		T result = pollInternal(valueCheck);
		completeIfNecessary();
		return result;
	}

	/**
	 * Invoked when a value is added.
	 *
	 * @param value The added value.
	 * @param alreadyDispatched {@code true} if the item will skip the queue because a poller was already waiting for an
	 * item; {@code false} if the item was actually added to the queue.
	 */
	protected void onAdded(T value, boolean alreadyDispatched) {
	}

	/**
	 * Invoked when a value is polled.
	 *
	 * @param value The polled value.
	 */
	protected void onPolled(T value) {
	}

	/**
	 * Invoked when the queue is completed.
	 */
	protected void onCompleted() {
	}

	/**
	 * Immediately polls the element from the head of the queue if one is available, otherwise returns {@code null}.
	 *
	 * @param valueCheck The test on the head element that must succeed to poll.
	 * @return The element from the head of the queue; or {@code null} if the queue is empty or the first element does
	 * not match the predicate.
	 */
	private T pollInternal(@Nullable Predicate<? super T> valueCheck) {
		T value;
		boolean polled;
		synchronized (syncObject) {
			if (queueElements != null && !queueElements.isEmpty() && (valueCheck == null || valueCheck.test(queueElements.peek()))) {
				value = queueElements.poll();
				polled = true;
			} else {
				value = null;
				polled = false;
			}
		}

		if (polled) {
			onPolled(value);
		}

		return value;
	}

	private void cancelPoller(CompletableFuture<T> poller) {
		synchronized (syncObject) {
			if (pollingFutures != null) {
				pollingFutures.remove(poller);
			}
		}
	}

	/**
	 * Transitions this queue to a completed state if signaled and the queue is empty.
	 */
	private void completeIfNecessary() {
		// important because we'll transition a task to complete.
		assert !Thread.holdsLock(syncObject);

		boolean transitionFuture;
		boolean invokeOnCompleted = false;
		List<CompletableFuture<T>> futuresToCancel = null;
		synchronized (syncObject) {
			transitionFuture = completeSignaled && (queueElements == null || queueElements.isEmpty());
			if (transitionFuture) {
				invokeOnCompleted = !onCompletedInvoked;
				onCompletedInvoked = true;

				if (pollingFutures != null) {
					for (CompletableFuture<T> pollingFuture : pollingFutures) {
						if (pollingFuture.isDone()) {
							continue;
						}

						if (futuresToCancel == null) {
							futuresToCancel = new ArrayList<>();
						}

						futuresToCancel.add(pollingFuture);
					}

					pollingFutures.clear();
				}
			}
		}

		if (transitionFuture) {
			if (future != null) {
				future.complete(null);
			}

			if (invokeOnCompleted) {
				onCompleted();
			}

			if (futuresToCancel != null) {
				for (CompletableFuture<T> futureToCancel : futuresToCancel) {
					futureToCancel.cancel(true);
				}
			}
		}
	}
}
