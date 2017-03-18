// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Copied from Microsoft/vs-threading@14f77875.
 */
public class JoinableFutureFactoryTest extends JoinableFutureTestBase {
	@Test
	public void testOnTransitioningToMainThread_DoesNotHoldPrivateLock() {
		this.simulateUIThread(() -> Async.awaitAsync(
			// Get off the UI thread first so that we can transition (back) to it.
			ForkJoinPool.commonPool(),
			() -> {
				JTFWithTransitioningBlock jtf = new JTFWithTransitioningBlock(this.context);
				AtomicBoolean noDeadlockDetected = new AtomicBoolean(true);
				jtf.onTransitioningToMainThreadCallback = j -> {
					// While blocking this thread, let's get another thread going that ends up calling into JTF.
					// This test code may lead folks to say "ya, but is this realistic? Who would do this?"
					// But this is just the simplest repro of a real hang we had in VS2015, where the code
					// in the JTF overridden method called into another service, which also had a private lock
					// but who had issued that private lock to another thread, that was blocked waiting for
					// JTC.Factory to return.
					CompletableFuture<Void> otherThread = Futures.runAsync(() -> {
						// It so happens as of the time of this writing that the Factory property
						// always requires a SyncContextLock. If it ever stops needing that,
						// we'll need to change this delegate to do something else that requires it.
						this.context.getFactory();
					});

					try {
						// Wait up to the timeout interval. Don't Assert here because
						// throwing in this callback results in JTF calling Environment.FailFast
						// which crashes the test runner. We'll assert on this local boolean
						// after we exit this critical section.
						otherThread.get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
					} catch (InterruptedException | ExecutionException | TimeoutException ex) {
						noDeadlockDetected.set(false);
					}
				};

				JoinableFuture<Void> jt = jtf.runAsync(() -> Async.awaitAsync(jtf.switchToMainThreadAsync()));

				// If a deadlock is detected, that means the JTF called out to our code
				// while holding a private lock. Bad thing.
				Assert.assertTrue(noDeadlockDetected.get());

				return Futures.completedNull();
			}));
	}

	@Test
	public void testOnTransitionedToMainThread_DoesNotHoldPrivateLock() {
		this.simulateUIThread(() -> Async.awaitAsync(
			// Get off the UI thread first so that we can transition (back) to it.
			ForkJoinPool.commonPool(),
			() -> {
				JTFWithTransitioningBlock jtf = new JTFWithTransitioningBlock(this.context);
				AtomicBoolean noDeadlockDetected = new AtomicBoolean(true);
				jtf.onTransitionedToMainThreadCallback = (j, c) -> {
					// While blocking this thread, let's get another thread going that ends up calling into JTF.
					// This test code may lead folks to say "ya, but is this realistic? Who would do this?"
					// But this is just the simplest repro of a real hang we had in VS2015, where the code
					// in the JTF overridden method called into another service, which also had a private lock
					// but who had issued that private lock to another thread, that was blocked waiting for
					// JTC.Factory to return.
					CompletableFuture<Void> otherThread = Futures.runAsync(() -> {
						// It so happens as of the time of this writing that the Factory property
						// always requires a SyncContextLock. If it ever stops needing that,
						// we'll need to change this delegate to do something else that requires it.
						context.getFactory();
					});

					try {
						// Wait up to the timeout interval. Don't Assert here because
						// throwing in this callback results in JTF calling Environment.FailFast
						// which crashes the test runner. We'll assert on this local boolean
						// after we exit this critical section.
						otherThread.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
					} catch (InterruptedException | ExecutionException | TimeoutException ex) {
						noDeadlockDetected.set(false);
					}
				};

				jtf.run(() -> Async.awaitAsync(jtf.switchToMainThreadAsync()));

				// If a deadlock is detected, that means the JTF called out to our code
				// while holding a private lock. Bad thing.
				Assert.assertTrue(noDeadlockDetected.get());

				return Futures.completedNull();
			}));
	}

	/**
	 * A {@link JoinableFutureFactory} that allows a test to inject code in the main thread transition events.
	 */
	private static class JTFWithTransitioningBlock extends JoinableFutureFactory {

		Consumer<JoinableFuture<?>> onTransitioningToMainThreadCallback;
		BiConsumer<JoinableFuture<?>, Boolean> onTransitionedToMainThreadCallback;

		public JTFWithTransitioningBlock(JoinableFutureContext owner) {
			super(owner);
		}

		@Override
		protected void onTransitioningToMainThread(JoinableFuture<?> joinableFuture) {
			super.onTransitioningToMainThread(joinableFuture);
			if (onTransitioningToMainThreadCallback != null) {
				onTransitioningToMainThreadCallback.accept(joinableFuture);
			}
		}

		@Override
		protected void onTransitionedToMainThread(JoinableFuture<?> joinableFuture, boolean canceled) {
			super.onTransitionedToMainThread(joinableFuture, canceled);
			if (onTransitionedToMainThreadCallback != null) {
				onTransitionedToMainThreadCallback.accept(joinableFuture, canceled);
			}
		}
	}
}
