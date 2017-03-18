// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

class AsyncFlowControl implements AutoCloseable {
	private final Thread thread;
	private boolean closed;

	public AsyncFlowControl() {
		this.thread = Thread.currentThread();
	}

	@Override
	public void close() {
		if (thread != Thread.currentThread()) {
			throw new UnsupportedOperationException("This object cannot be used on a different thread from where it was created.");
		}

		if (closed) {
			throw new IllegalStateException("This object is already closed.");
		}

		closed = true;
		ExecutionContext.restoreFlow();
	}
}
