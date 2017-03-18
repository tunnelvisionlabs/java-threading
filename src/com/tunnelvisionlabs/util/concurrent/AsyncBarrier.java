// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.Requires;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An asynchronous barrier that blocks the signaler until all other participants have signaled.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncBarrier {
	/**
	 * The number of participants being synchronized.
	 */
	private final int participantCount;

	/**
	 * The number of participants that have not yet signaled the barrier.
	 */
	private final AtomicInteger remainingParticipants = new AtomicInteger();

	/**
	 * The set of participants who have reached the barrier, with their futures that can resume those participants.
	 */
	private ConcurrentLinkedDeque<CompletableFuture<Void>> waiters;

	/**
	 * Constructs a new instance of the {@link AsyncBarrier} class.
	 *
	 * @param participants The number of participants.
	 */
	public AsyncBarrier(int participants) {
		Requires.range(participants > 0, "participants");

		this.participantCount = participants;
		this.remainingParticipants.set(participants);
		this.waiters = new ConcurrentLinkedDeque<>();
	}

	/**
	 * Signals that a participant has completed work, and returns a future that completes when all other participants
	 * have also completed work.
	 *
	 * @return A future.
	 */
	public final CompletableFuture<Void> signalAndWait() {
		CompletableFuture<Void> future = new CompletableFuture<>();
		this.waiters.push(future);
		if (remainingParticipants.decrementAndGet() == 0) {
			remainingParticipants.set(participantCount);
			ConcurrentLinkedDeque<CompletableFuture<Void>> localWaiters = this.waiters;
			this.waiters = new ConcurrentLinkedDeque<>();

			for (CompletableFuture<Void> waiter : localWaiters) {
				ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> {
					waiter.complete(null);
				}));
			}
		}

		return future;
	}
}
