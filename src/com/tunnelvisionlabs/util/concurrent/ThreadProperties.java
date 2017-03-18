// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

enum ThreadProperties {
	;

	private static final ThreadLocal<ExecutionContext> EXECUTION_CONTEXT = new ThreadLocal<>();
	private static final ThreadLocal<SynchronizationContext> SYNCHRONIZATION_CONTEXT = new ThreadLocal<>();

	static ExecutionContext getExecutionContext(Thread currentThread) {
		assert currentThread == Thread.currentThread();
		return EXECUTION_CONTEXT.get();
	}

	static void setExecutionContext(Thread currentThread, ExecutionContext executionContext) {
		assert currentThread == Thread.currentThread();
		EXECUTION_CONTEXT.set(executionContext);
	}

	static SynchronizationContext getSynchronizationContext(Thread currentThread) {
		assert currentThread == Thread.currentThread();
		return SYNCHRONIZATION_CONTEXT.get();
	}

	static void setSynchronizationContext(Thread currentThread, SynchronizationContext synchronizationContext) {
		assert currentThread == Thread.currentThread();
		SYNCHRONIZATION_CONTEXT.set(synchronizationContext);
	}
}
