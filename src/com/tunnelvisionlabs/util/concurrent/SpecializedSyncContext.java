// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Report;

/**
 * A class that applies and reverts changes to the {@link SynchronizationContext}.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public final class SpecializedSyncContext implements Disposable {
	/**
	 * The {@link SynchronizationContext} to restore when {@link #close()} is invoked.
	 */
	private final SynchronizationContext prior;

	/**
	 * The {@link SynchronizationContext} applied when this instance was constructed.
	 */
	private final SynchronizationContext appliedContext;

	/**
	 * A value indicating whether to check that the applied synchronization context is still the current one when the
	 * original is restored.
	 */
	private final boolean checkForChangesOnRevert;

	/**
	 * Constructs a new instance of the {@link SpecializedSyncContext} class.
	 */
	private SpecializedSyncContext(SynchronizationContext syncContext, boolean checkForChangesOnRevert) {
		this.prior = SynchronizationContext.getCurrent();
		this.appliedContext = syncContext;
		this.checkForChangesOnRevert = checkForChangesOnRevert;
		SynchronizationContext.setSynchronizationContext(syncContext);
	}

	/**
	 * Applies the specified {@link SynchronizationContext} to the caller's context.
	 *
	 * @param syncContext The synchronization context to apply.
	 */
	@NotNull
	public static SpecializedSyncContext apply(@Nullable SynchronizationContext syncContext) {
		return apply(syncContext, true);
	}

	/**
	 * Applies the specified {@link SynchronizationContext} to the caller's context.
	 *
	 * @param syncContext The synchronization context to apply.
	 * @param checkForChangesOnRevert A value indicating whether to check that the applied synchronization context is
	 * still the current one when the original is restored.
	 */
	@NotNull
	public static SpecializedSyncContext apply(@Nullable SynchronizationContext syncContext, boolean checkForChangesOnRevert) {
		return new SpecializedSyncContext(syncContext, checkForChangesOnRevert);
	}

	/**
	 * Reverts the {@link SynchronizationContext} to its previous instance.
	 */
	@Override
	public void close() {
		Report.reportIf(checkForChangesOnRevert && SynchronizationContext.getCurrent() != this.appliedContext);
		SynchronizationContext.setSynchronizationContext(this.prior);
	}
}
