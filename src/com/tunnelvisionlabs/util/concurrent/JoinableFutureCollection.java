// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A collection of joinable futures.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureCollection implements Iterable<JoinableFuture<?>> {
	/**
	 * The set of joinable futures that belong to this collection -- that is, the set of joinable futures that are
	 * implicitly Joined when folks Join this collection. The value is the number of times the joinable was added to
	 * this collection (and not yet removed) if this collection is ref counted; otherwise the value is always 1.
	 */
	private final Map<JoinableFuture<?>, Integer> joinables = new WeakHashMap<>();

	/**
	 * The set of joinable futures that have Joined this collection -- that is, the set of joinable futures that are
	 * interested in the completion of any and all joinable futures that belong to this collection. The value is the
	 * number of times a particular joinable future has Joined this collection.
	 */
	private final Map<JoinableFuture<?>, Integer> joiners = new WeakHashMap<>();

	/**
	 * A value indicating whether joinable futures are only removed when completed or removed as many times as they were
	 * added.
	 */
	private final boolean refCountAddedJobs;

	/**
	 * A human-readable name that may appear in hang reports.
	 */
	private String displayName;

	/**
	 * An event that is set when the collection is empty. (lazily initialized)
	 */
	private final AtomicReference<AsyncManualResetEvent> emptyEvent = new AtomicReference<>();

	/**
	 * This is the backing field for {@link #getContext()}.
	 */
	private final JoinableFutureContext context;

	/**
	 * Constructs a new instance of the {@link JoinableFutureCollection} class.
	 *
	 * @param context The {@link JoinableFutureContext} instance to which this collection applies.
	 */
	public JoinableFutureCollection(@NotNull JoinableFutureContext context) {
		this(context, false);
	}

	/**
	 * Constructs a new instance of the {@link JoinableFutureCollection} class.
	 *
	 * @param context The {@link JoinableFutureContext} instance to which this collection applies.
	 * @param refCountAddedJobs {@code true} if {@link JoinableFuture} instances added to the collection multiple times
	 * should remain in the collection until they are either removed the same number of times or until they are
	 * completed; {@code false} causes the first {@link #remove} call for a {@link JoinableFuture} to remove it from
	 * this collection regardless how many times it had been added.
	 */
	public JoinableFutureCollection(@NotNull JoinableFutureContext context, boolean refCountAddedJobs) {
		Requires.notNull(context, "context");
		this.context = context;
		this.refCountAddedJobs = refCountAddedJobs;
	}

	/**
	 * Gets the {@link JoinableFutureContext} to which this collection belongs.
	 */
	@NotNull
	public final JoinableFutureContext getContext() {
		return context;
	}

	/**
	 * Gets a human-readable name that may appear in hang reports.
	 */
	public final String getDisplayName() {
		return displayName;
	}

	/**
	 * Sets a human-readable name that may appear in hang reports.
	 *
	 * <p>This property should <em>not</em> be set to a value that may disclose personally identifiable information or
	 * other confidential data since this value may be included in hang reports sent to a third party.</p>
	 */
	public final void setDisplayName(String value) {
		displayName = value;
	}

	/**
	 * Adds the specified joinable future to this collection.
	 *
	 * @param joinableFuture The joinable future to add to the collection.
	 */
	public final void add(@NotNull JoinableFuture<?> joinableFuture) {
		Requires.notNull(joinableFuture, "joinableFuture");
		if (joinableFuture.getFactory().getContext() != this.getContext()) {
			Requires.argument(false, "joinableFuture", "JoinableTaskContextAndCollectionMismatch");
		}

		if (!joinableFuture.isDone()) {
			synchronized (getContext().getSyncContextLock()) {
				Integer refCount = joinables.get(joinableFuture);
				if (refCount == null || refCountAddedJobs) {
					if (refCount == null) {
						refCount = 0;
					}

					joinables.put(joinableFuture, refCount + 1);
					if (refCount == 0) {
						joinableFuture.onAddedToCollection(this);

						// Now that we've added a joinable future to our collection, any folks who
						// have already joined this collection should be joined to this joinable future.
						for (JoinableFuture<?> joiner : joiners.keySet()) {
							// We can discard the JoinRelease result of addDependency
							// because we directly disjoin without that helper class.
							joiner.addDependency(joinableFuture);
						}
					}
				}

				if (emptyEvent.get() != null) {
					emptyEvent.get().reset();
				}
			}
		}
	}

	/**
	 * Removes the specified joinable future from this collection, or decrements the ref count if this collection tracks
	 * that.
	 *
	 * @param joinableFuture The joinable future to remove.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final void remove(@NotNull JoinableFuture<?> joinableFuture) {
		Requires.notNull(joinableFuture, "joinableFuture");

		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (getContext().getSyncContextLock()) {
				Integer refCount = joinables.get(joinableFuture);
				if (refCount != null) {
					if (refCount == 1 || joinableFuture.isDone()) {
						// remove regardless of ref count if job is completed
						joinables.remove(joinableFuture);
						joinableFuture.onRemovedFromCollection(this);

						// Now that we've removed a joinable future from our collection, any folks who
						// have already joined this collection should be disjoined to this joinable future
						// as an efficiency improvement so we don't grow our weak collections unnecessarily.
						for (JoinableFuture<?> joiner : joiners.keySet()) {
							// We can discard the JoinRelease result of addDependency
							// because we directly disjoin without that helper class.
							joiner.removeDependency(joinableFuture);
						}

						if (emptyEvent.get() != null && joinables.isEmpty()) {
							emptyEvent.get().set();
						}
					} else {
						joinables.put(joinableFuture, refCount - 1);
					}
				}
			}
		}
	}

	/**
	 * Shares access to the main thread that the caller's {@link JoinableFuture} may have (if any) with all
	 * {@link JoinableFuture} instances in this collection until the returned value is closed.
	 *
	 * <p>Calling this method when the caller is not executing within a {@link JoinableFuture} safely no-ops.</p>
	 *
	 * @return A value to close to revert the join.
	 */
	@NotNull
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final JoinRelease join() {
		JoinableFuture<?> ambientJob = getContext().getAmbientFuture();
		if (ambientJob == null) {
			// The caller isn't running in the context of a joinable future, so there is nothing to join with this collection.
			return JoinRelease.EMPTY;
		}

		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (getContext().getSyncContextLock()) {
				Integer count = joiners.get(ambientJob);
				if (count == null) {
					count = 0;
				}

				joiners.put(ambientJob, count + 1);
				if (count == 0) {
					// The joining job was not previously joined to this collection,
					// so we need to join each individual job within the collection now.
					for (JoinableFuture<?> joinable : joinables.keySet()) {
						ambientJob.addDependency(joinable);
					}
				}

				return new JoinRelease(this, ambientJob);
			}
		}
	}

	/**
	 * Joins the caller's context to this collection until the collection is empty.
	 *
	 * @return A future that completes when this collection is empty.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final CompletableFuture<Void> joinUntilEmptyAsync() {
		return Async.runAsync(() -> {
			if (emptyEvent.get() == null) {
				// We need a read lock to protect against the emptiness of this collection changing
				// while we're setting the initial set state of the new event.
				try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getContext().getNoMessagePumpSynchronizationContext())) {
					synchronized (getContext().getSyncContextLock()) {
						// We use interlocked here to mitigate race conditions in lazily initializing this field.
						// We *could* take a write lock above, but that would needlessly increase lock contention.
						emptyEvent.compareAndSet(null, new AsyncManualResetEvent(joinables.isEmpty()));
					}
				}
			}

			return Async.usingAsync(
				join(),
				() -> Async.awaitAsync(Async.configureAwait(emptyEvent.get().waitAsync(), false)));
		});
	}

	/**
	 * Checks whether the specified joinable future is a member of this collection.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final boolean contains(@NotNull JoinableFuture<?> joinableFuture) {
		Requires.notNull(joinableFuture, "joinableFuture");

		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (getContext().getSyncContextLock()) {
				return joinables.containsKey(joinableFuture);
			}
		}
	}

	/**
	 * Iterates the futures in this collection.
	 */
	@Override
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	public final Iterator<JoinableFuture<?>> iterator() {
		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getContext().getNoMessagePumpSynchronizationContext())) {
			List<JoinableFuture<?>> joinablesList;
			synchronized (getContext().getSyncContextLock()) {
				joinablesList = new ArrayList<>(joinables.keySet());
			}

			return joinablesList.iterator();
		}
	}

	/**
	 * Breaks a join formed between the specified joinable future and this collection.
	 *
	 * @param joinableFuture The joinable future that had previously joined this collection, and that now intends to
	 * revert it.
	 */
	@SuppressWarnings(Suppressions.TRY_SCOPE)
	final void disjoin(@NotNull JoinableFuture<?> joinableFuture) {
		Requires.notNull(joinableFuture, "joinableFuture");

		try (SpecializedSyncContext syncContext = SpecializedSyncContext.apply(getContext().getNoMessagePumpSynchronizationContext())) {
			synchronized (getContext().getSyncContextLock()) {
				Integer count = joiners.get(joinableFuture);
				if (count == null) {
					count = 0;
				}

				if (count == 1) {
					joiners.remove(joinableFuture);

					// We also need to disjoin this joinable future from all joinable futures in this collection.
					for (JoinableFuture<?> joinable : joinables.keySet()) {
						joinableFuture.removeDependency(joinable);
					}
				} else {
					joiners.put(joinableFuture, count - 1);
				}
			}
		}
	}

	/**
	 * A value whose closing cancels a {@link #join} operation.
	 */
	public static final class JoinRelease implements Disposable {

		static final JoinRelease EMPTY = new JoinRelease();

		private JoinableFuture<?> joinedJob;
		private JoinableFuture<?> joiner;
		private JoinableFutureCollection joinedJobCollection;

		private JoinRelease() {
		}

		/**
		 * Constructs a new instance of the {@link JoinRelease} class.
		 *
		 * @param joined The Main thread controlling {@link SingleThreadSynchronizationContext} to use to accelerate
		 * execution of Main thread bound work.
		 * @param joiner The instance that created this value.
		 */
		JoinRelease(@NotNull JoinableFuture<?> joined, @NotNull JoinableFuture<?> joiner) {
			Requires.notNull(joined, "joined");
			Requires.notNull(joiner, "joiner");

			this.joinedJobCollection = null;
			this.joinedJob = joined;
			this.joiner = joiner;
		}

		/**
		 * Constructs a new instance of the {@link JoinRelease} class.
		 *
		 * @param jobCollection The collection of joinable futures that has been joined.
		 * @param joiner The instance that created this value.
		 */
		JoinRelease(@NotNull JoinableFutureCollection jobCollection, @NotNull JoinableFuture<?> joiner) {
			Requires.notNull(jobCollection, "jobCollection");
			Requires.notNull(joiner, "joiner");

			this.joinedJobCollection = jobCollection;
			this.joinedJob = null;
			this.joiner = joiner;
		}

		/**
		 * Cancels the {@link #join()} operation.
		 */
		@Override
		public void close() {
			if (joinedJob != null) {
				joinedJob.removeDependency(joiner);
				joinedJob = null;
			}

			if (joinedJobCollection != null) {
				joinedJobCollection.disjoin(joiner);
				joinedJobCollection = null;
			}

			this.joiner = null;
		}
	}

}
