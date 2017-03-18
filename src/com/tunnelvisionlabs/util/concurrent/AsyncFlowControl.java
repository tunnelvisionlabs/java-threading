// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

public final class AsyncFlowControl implements Disposable {
	private Thread thread;

	public AsyncFlowControl() {
	}

	void initialize(Thread currentThread) {
		assert currentThread == Thread.currentThread();
		thread = currentThread;
	}

	@Override
	public void close() {
		if (thread == null) {
			throw new IllegalStateException("Cannot reuse AsyncFlowControl");
		}

		if (thread != Thread.currentThread()) {
			throw new UnsupportedOperationException("This object cannot be used on a different thread from where it was created.");
		}

		if (!ExecutionContext.isFlowSuppressed()) {
			throw new IllegalStateException("This object is already closed.");
		}

		thread = null;
		ExecutionContext.restoreFlow();
	}
}
