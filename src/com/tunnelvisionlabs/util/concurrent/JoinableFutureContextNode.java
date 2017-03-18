// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A customizable source of {@link JoinableFutureFactory} instances.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureContextNode {
	/**
	 * The inner {@link JoinableFutureContext}.
	 */
	private final JoinableFutureContext context;

	/**
	 * A single joinable future factory that itself cannot be joined.
	 */
	private final AtomicReference<JoinableFutureFactory> nonJoinableFactory = new AtomicReference<>();

	/**
	 * Constructs a new instance of the {@link JoinableFutureContextNode} class.
	 *
	 * @param context The inner {@link JoinableFutureContext}.
	 */
	public JoinableFutureContextNode(@NotNull JoinableFutureContext context) {
		Requires.notNull(context, "context");
		this.context = context;
	}

	/**
	 * Gets the factory which creates joinable futures that do not belong to a joinable future collection.
	 */
	public final JoinableFutureFactory getFactory() {
		if (nonJoinableFactory.get() == null) {
			JoinableFutureFactory factory = createDefaultFactory();
			nonJoinableFactory.compareAndSet(null, factory);
		}

		return nonJoinableFactory.get();
	}

	/**
	 * Gets the main thread that can be shared by futures created by this context.
	 */
	@NotNull
	public final Thread getMainThread() {
		return context.getMainThread();
	}

	/**
	 * Gets a value indicating whether the caller is executing on the main thread.
	 */
	public final boolean isOnMainThread() {
		return context.isOnMainThread();
	}

	/**
	 * Gets the inner wrapped context.
	 */
	@NotNull
	public final JoinableFutureContext getContext() {
		return context;
	}

	/**
	 * Creates a joinable future factory that automatically adds all created futures to a collection that can be jointly
	 * joined.
	 *
	 * @param collection The collection that all futures should be added to.
	 */
	@NotNull
	public JoinableFutureFactory createFactory(@NotNull JoinableFutureCollection collection) {
		return context.createFactory(collection);
	}

	/**
	 * Creates a collection for in-flight joinable futures.
	 *
	 * @return A new joinable future collection.
	 */
	@NotNull
	public final JoinableFutureCollection createCollection() {
		return context.createCollection();
	}

	/**
	 * Conceals any {@link JoinableFuture} the caller is associated with until the returned value is closed.
	 *
	 * @return A value to close to restore visibility into the caller's associated {@link JoinableFuture}, if any.
	 *
	 * @see JoinableFutureContext#suppressRelevance()
	 */
	@NotNull
	public final JoinableFutureContext.RevertRelevance suppressRelevance() {
		return context.suppressRelevance();
	}

	/**
	 * Gets a value indicating whether the main thread is blocked for the caller's completion.
	 */
	public final boolean isMainThreadBlocked() {
		return context.isMainThreadBlocked();
	}

	/**
	 * Invoked when a hang is suspected to have occurred involving the main thread.
	 *
	 * <p>A single hang occurrence may invoke this method multiple times, with increasing
	 * {@link JoinableFutureContext.HangDetails#getNotificationCount()} values in the {@code details} parameter.</p>
	 *
	 * @param details Describes the hang in detail.
	 */
	protected void onHangDetected(@NotNull JoinableFutureContext.HangDetails details) {
	}

	/**
	 * Invoked when an earlier hang report is false alarm.
	 *
	 * @param hangDuration The duration of the total waiting time
	 * @param hangId A GUID that uniquely identifies the earlier hang report.
	 */
	protected void onFalseHangDetected(Duration hangDuration, UUID hangId) {
	}

	/**
	 * Creates a factory without a {@link JoinableFutureCollection}.
	 *
	 * <p>Used for initializing the {@link #getFactory()} property.</p>
	 */
	@NotNull
	protected JoinableFutureFactory createDefaultFactory() {
		return this.context.createDefaultFactory();
	}

	/**
	 * Registers with the inner {@link JoinableFutureContext} to receive hang notifications.
	 *
	 * @return A value to close to cancel hang notifications.
	 */
	@NotNull
	protected final Disposable registerOnHangDetected() {
		return this.context.registerHangNotifications(this);
	}

}
