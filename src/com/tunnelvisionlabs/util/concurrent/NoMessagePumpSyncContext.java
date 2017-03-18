// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

/**
 * A {@link SynchronizationContext} whose synchronously blocking Wait method does not allow any reentrancy via the
 * message pump.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class NoMessagePumpSyncContext extends SynchronizationContext {
	/**
	 * A shared singleton.
	 */
	private static final SynchronizationContext DEFAULT_INSTANCE = new NoMessagePumpSyncContext();

	/**
	 * Constructs a new instance of the {@link NoMessagePumpSyncContext} class.
	 */
	public NoMessagePumpSyncContext() {
		// This is required so that our override of wait is invoked.
		this.setWaitNotificationRequired();
	}

	/**
	 * Gets a shared instance of this class.
	 */
	public static SynchronizationContext getDefault() {
		return DEFAULT_INSTANCE;
	}

//#if DESKTOP
//        /// <summary>
//        /// Synchronously blocks without a message pump.
//        /// </summary>
//        /// <param name="waitHandles">An array of type <see cref="T:System.IntPtr" /> that contains the native operating system handles.</param>
//        /// <param name="waitAll">true to wait for all handles; false to wait for any handle.</param>
//        /// <param name="millisecondsTimeout">The number of milliseconds to wait, or <see cref="F:System.Threading.Timeout.Infinite" /> (-1) to wait indefinitely.</param>
//        /// <returns>
//        /// The array index of the object that satisfied the wait.
//        /// </returns>
//        public override int Wait(IntPtr[] waitHandles, bool waitAll, int millisecondsTimeout)
//        {
//            Requires.NotNull(waitHandles, nameof(waitHandles));
//            return NativeMethods.WaitForMultipleObjects((uint)waitHandles.Length, waitHandles, waitAll, (uint)millisecondsTimeout);
//        }
//#endif

}
