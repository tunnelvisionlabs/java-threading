// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.Test;

public class AsyncBarrierTest extends TestBase {
	@Test
	public void testZeroParticipantsThrow() {
		thrown.expect(IllegalArgumentException.class);
		new AsyncBarrier(0);
	}

	@Test
	public void testOneParticipant() {
		AsyncBarrier barrier = new AsyncBarrier(1);
		CompletableFuture<Void> future = barrier.signalAndWait();
		future.join();
	}

	@Test
	public void testTwoParticipants() {
		CompletableFuture<Void> future = multipleParticipantsHelperAsync(2, 3);
		future.join();
	}

	@Test
	public void testManyParticipantsAndSteps() {
		CompletableFuture<Void> future = multipleParticipantsHelperAsync(100, 50);
		future.join();
	}

	private CompletableFuture<Void> multipleParticipantsHelperAsync(int participants, int steps) {
		Requires.range(participants > 0, "participants");
		Requires.range(steps > 0, "steps");

		// 1 for test coordinator
		AsyncBarrier barrier = new AsyncBarrier(1 + participants);

		final int[] currentStepForActors = new int[participants];
		List<CompletableFuture<Void>> actorsFinishedFutures = new ArrayList<>();
		AsyncAutoResetEvent actorReady = new AsyncAutoResetEvent();
		for (int i = 0; i < participants; i++) {
			int participantIndex = i;
			Consumer<Integer> progress = step -> {
				currentStepForActors[participantIndex] = step;
				actorReady.set();
			};

			actorsFinishedFutures.add(actorAsync(barrier, steps, progress));
		}

		return Async.forAsync(
			() -> 1,
			i -> i <= steps,
			i -> i + 1,
			i -> Async.unwrap(Async
				.whileAsync(
					// Wait until all actors report having completed this step.
					() -> !all(currentStepForActors, step -> Objects.equals(step, i)),
					() -> {
						// Wait for someone to signal a change has been made to the array.
						return Async.awaitAsync(actorReady.waitAsync());
					})
				.thenApply(
					// Give the last signal to proceed to the next step.
					ignored -> Async.awaitAsync(barrier.signalAndWait()))));
	}

	private static boolean all(int[] array, Predicate<Integer> predicate) {
		for (int i = 0; i < array.length; i++) {
			if (!predicate.test(array[i])) {
				return false;
			}
		}

		return true;
	}

	private CompletableFuture<Void> actorAsync(AsyncBarrier barrier, int steps, Consumer<Integer> progress) {
		Requires.notNull(barrier, "barrier");
		Requires.range(steps >= 0, "steps");
		Requires.notNull(progress, "progress");

		return Async.forAsync(
			() -> 1,
			i -> i <= steps,
			i -> i + 1,
			i -> Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					progress.accept(i);
					return Async.awaitAsync(barrier.signalAndWait());
				}));
	}
}
