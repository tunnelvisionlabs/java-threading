// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;

/**
 * An awaiter that can be pre-created, and later immediately execute its one scheduled continuation.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
class InlineResumable implements Awaitable<Void>, Awaiter<Void> {
	/**
	 * The continuation that has been scheduled.
	 */
	private Runnable continuation;

	/**
	 * The current {@link SynchronizationContext} as of when the continuation was scheduled.
	 */
	private SynchronizationContext capturedSynchronizationContext;

	/**
	 * Whether {@link #resume} has been called already.
	 */
	private boolean resumed;

	/**
	 * Gets a value indicating whether an awaiting expression should yield.
	 *
	 * @return Always {@code false}
	 */
	@Override
	public final boolean isDone() {
		return resumed;
	}

	/**
	 * Does and returns nothing.
	 */
	@Override
	public final Void getResult() {
		return null;
	}

	/**
	 * Stores the continuation for later execution when {@link #resume()} is invoked.
	 *
	 * @param continuation The delegate to execute later.
	 */
	@Override
	public void onCompleted(@NotNull Runnable continuation) {
		Requires.notNull(continuation, "continuation");
		assert this.continuation == null : "Only one continuation is supported.";

		this.capturedSynchronizationContext = SynchronizationContext.getCurrent();
		this.continuation = ExecutionContext.wrap(continuation);
	}

	/**
	 * Gets this instance. This method makes this awaiter double as its own awaitable.
	 *
	 * @return This instance.
	 */
	@NotNull
	@Override
	public final InlineResumable getAwaiter() {
		return this;
	}

	/**
	 * Executes the continuation immediately, on the caller's thread.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final void resume() {
		this.resumed = true;
		Runnable continuation = this.continuation;
		this.continuation = null;
		try (SpecializedSyncContext context = SpecializedSyncContext.apply(this.capturedSynchronizationContext)) {
			if (continuation != null) {
				continuation.run();
			}
		}
	}

}
