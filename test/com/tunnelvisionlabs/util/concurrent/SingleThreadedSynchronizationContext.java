// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import com.tunnelvisionlabs.util.validation.Verify;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A single-threaded synchronization context, akin to the {@code DispatcherSynchronizationContext} and
 * {@code WindowsFormsSynchronizationContext}.
 */
enum SingleThreadedSynchronizationContext {
	;

	public interface Frame {

		boolean getContinue();

		void setContinue(boolean value);
	}

	public static SynchronizationContext create() {
		return new SyncContext();
	}

	public static boolean isSingleThreadedSyncContext(SynchronizationContext context) {
		return (context instanceof SyncContext);
	}

	public static Frame newFrame() {
		return new SyncContext.FrameImpl();
	}

	public static void pushFrame(@NotNull SynchronizationContext context, @NotNull Frame frame) {
		Requires.notNull(context, "context");
		Requires.notNull(frame, "frame");

		SyncContext ctxt = (SyncContext)context;
		ctxt.pushFrame((SyncContext.FrameImpl)frame);
	}

	private static class SyncContext extends SynchronizationContext {

		private final Deque<Message<?>> messageQueue = new ArrayDeque<>();

		private final long ownedThreadId = Thread.currentThread().getId();

		@Override
		public <T> void post(Consumer<T> d, T state) {
			ExecutionContext ctxt = ExecutionContext.capture();
			synchronized (this.messageQueue) {
				messageQueue.add(new Message<>(d, state, ctxt));
				messageQueue.notifyAll();
			}
		}

		@Override
		public <T> void send(Consumer<T> d, T state) {
			Requires.notNull(d, "d");

			if (this.ownedThreadId == Thread.currentThread().getId()) {
				d.accept(state);
			} else {
				AtomicReference<Throwable> caughtException = new AtomicReference<>();
				CompletableFuture<Void> event = new CompletableFuture<>();
				ExecutionContext ctxt = ExecutionContext.capture();
				synchronized (messageQueue) {
					messageQueue.add(new Message<>(
						s
						-> {
						try {
							d.accept(state);
						} catch (Throwable ex) {
							caughtException.set(ex);
						} finally {
							event.complete(null);
						}
					},
						null,
						ctxt));
					messageQueue.notifyAll();
				}

				event.join();
				if (caughtException.get() != null) {
					throw new CompletionException(caughtException.get());
				}
			}
		}

		public final void pushFrame(FrameImpl frame) {
			Requires.notNull(frame, "frame");
			Verify.operation(ownedThreadId == Thread.currentThread().getId(), "Can only push a message pump from the owned thread.");
			frame.setOwner(this);

			while (frame.getContinue()) {
				Message<?> message;
				synchronized (messageQueue) {
					// Check again now that we're holding the lock.
					if (!frame.getContinue()) {
						break;
					}

					if (!messageQueue.isEmpty()) {
						message = messageQueue.poll();
					} else {
						boolean interrupted = false;
						try {
							while (true) {
								try {
									this.messageQueue.wait();
									break;
								} catch (InterruptedException ex) {
									interrupted = true;
								}
							}
						} finally {
							if (interrupted) {
								Thread.currentThread().interrupt();
							}
						}

						continue;
					}
				}

				runMessage(message);
			}
		}

		private static <T> void runMessage(Message<T> message) {
			ExecutionContext.run(
				message.Context,
				state -> state.Callback.accept(state.State),
				message);
		}

		private static final class Message<State> {

			public final Consumer<State> Callback;
			public final State State;
			public final ExecutionContext Context;

			public Message(Consumer<State> d, State state, ExecutionContext ctxt) {
				this.Callback = d;
				this.State = state;
				this.Context = ctxt;
			}
		}

		public static class FrameImpl implements Frame {

			private SyncContext owner;
			private boolean _continue = true;

			@Override
			public boolean getContinue() {
				return _continue;
			}

			@Override
			public final void setContinue(boolean value) {
				Verify.operation(owner != null, "Frame not pushed yet.");

				this._continue = value;

				// Alert thread that may be blocked waiting for an incoming message
				// that it no longer needs to wait.
				if (!value) {
					synchronized (owner.messageQueue) {
						owner.messageQueue.notifyAll();
					}
				}
			}

			final void setOwner(SyncContext context) {
				if (context != this.owner) {
					Verify.operation(owner == null, "Frame already associated with a SyncContext");
					this.owner = context;
				}
			}
		}
	}
}
