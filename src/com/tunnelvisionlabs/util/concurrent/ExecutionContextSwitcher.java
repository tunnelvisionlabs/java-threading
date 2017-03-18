// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

final class ExecutionContextSwitcher {

	ExecutionContext executionContext;
	SynchronizationContext synchronizationContext;

	void undo(Thread currentThread) {
		assert currentThread == Thread.currentThread();

		if (ThreadProperties.getSynchronizationContext(currentThread) != synchronizationContext) {
			ThreadProperties.setSynchronizationContext(currentThread, synchronizationContext);
		}

		if (ThreadProperties.getExecutionContext(currentThread) != executionContext) {
			ThreadProperties.setExecutionContext(currentThread, executionContext);
		}
	}
}
