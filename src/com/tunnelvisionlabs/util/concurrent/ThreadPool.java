// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

enum ThreadPool {
	;

	private static final Executor COMMON_POOL_EXECUTOR = new Executor() {
		@Override
		public void execute(Runnable command) {
			ForkJoinPool.commonPool().execute(ExecutionContext.wrap(command));
		}
	};

	public static <T> void queueUserWorkItem(@NotNull Consumer<T> action, T state) {
		Runnable runnable = ExecutionContext.wrap(() -> action.accept(state));
		ForkJoinPool.commonPool().execute(runnable);
	}

	@NotNull
	public static Executor commonPool() {
		return COMMON_POOL_EXECUTOR;
	}
}
