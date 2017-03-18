// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

enum Futures {
	;

	private static final CompletableFuture<?> COMPLETED_CANCELLED;
	private static final CompletableFuture<?> COMPLETED_NULL;

	static {
		COMPLETED_CANCELLED = new CompletableFuture<Object>() {
			@Override
			public void obtrudeValue(Object value) {
				throw new UnsupportedOperationException("Not supported");
			}

			@Override
			public void obtrudeException(Throwable ex) {
				throw new UnsupportedOperationException("Not supported");
			}
		};

		COMPLETED_NULL = new CompletableFuture<Object>() {
			@Override
			public void obtrudeValue(Object value) {
				throw new UnsupportedOperationException("Not supported");
			}

			@Override
			public void obtrudeException(Throwable ex) {
				throw new UnsupportedOperationException("Not supported");
			}
		};

		COMPLETED_CANCELLED.cancel(false);
		COMPLETED_NULL.complete(null);
	}

	@NotNull
	public static <T> CompletableFuture<T> completedCancelled() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		CompletableFuture<T> result = (CompletableFuture<T>)COMPLETED_CANCELLED;
		return result;
	}

	@NotNull
	public static <T> CompletableFuture<T> completedFailed(@NotNull Throwable ex) {
		CompletableFuture<T> result = new CompletableFuture<>();
		if (!(ex instanceof CompletionException)) {
			ex = new CompletionException(ex);
		}

		result.completeExceptionally(ex);
		return result;
	}

	@NotNull
	public static <T> CompletableFuture<T> completedNull() {
		@SuppressWarnings(Suppressions.UNCHECKED_SAFE)
		CompletableFuture<T> result = (CompletableFuture<T>)COMPLETED_NULL;
		return result;
	}

	public static <T> CompletableFuture<T> fromException(@NotNull Throwable ex) {
		if (ex instanceof CancellationException) {
			return completedCancelled();
		} else {
			return completedFailed(ex);
		}
	}

	@NotNull
	public static <T> CompletableFuture<T> nonCancellationPropagating(@NotNull CompletableFuture<T> future) {
		CompletableFuture<T> wrapper = new CompletableFuture<>();
		future.whenComplete((result, exception) -> {
			if (future.isCancelled()) {
				wrapper.cancel(true);
			} else if (exception != null) {
				wrapper.completeExceptionally(exception);
			} else {
				wrapper.complete(result);
			}
		});

		return wrapper;
	}

	@NotNull
	public static <T> CompletableFuture<T> createPriorityHandler(@NotNull CompletableFuture<T> future, @NotNull StrongBox<CompletableFuture<T>> secondaryHandler) {
		if (future.isDone()) {
			secondaryHandler.value = future;
			return future;
		}

		secondaryHandler.value = new CompletableFuture<T>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				if (future.isCancelled()) {
					return super.cancel(mayInterruptIfRunning);
				}

				return future.cancel(mayInterruptIfRunning);
			}
		};

		CompletableFuture<T> priorityHandler = new CompletableFuture<T>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				if (future.isCancelled()) {
					return super.cancel(mayInterruptIfRunning);
				}

				return future.cancel(mayInterruptIfRunning);
			}
		};

		future.whenComplete((result, exception) -> {
			if (future.isCancelled()) {
				try {
					priorityHandler.cancel(true);
				} finally {
					secondaryHandler.value.cancel(true);
				}
			} else if (exception != null) {
				try {
					priorityHandler.completeExceptionally(exception);
				} finally {
					secondaryHandler.value.completeExceptionally(exception);
				}
			} else {
				try {
					priorityHandler.complete(result);
				} finally {
					secondaryHandler.value.complete(result);
				}
			}
		});

		return priorityHandler;
	}

	@NotNull
	public static <T> CompletableFuture<T> unwrap(@NotNull CompletableFuture<? extends CompletableFuture<T>> future) {
		CompletableFuture<T> result = new CompletableFuture<T>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				if (!future.cancel(mayInterruptIfRunning)) {
					if (!future.isDone() || future.isCompletedExceptionally()) {
						return false;
					}

					if (!future.join().cancel(mayInterruptIfRunning)) {
						return false;
					}
				}

				return super.cancel(mayInterruptIfRunning);
			}
		};

		future.whenComplete((outerResult, exception) -> {
			if (exception != null) {
				if (future.isCancelled()) {
					result.cancel(false);
				} else {
					result.completeExceptionally(exception);
				}
			} else {
				outerResult.whenComplete((innerResult, innerException) -> {
					if (innerException != null) {
						if (outerResult.isCancelled()) {
							result.cancel(false);
						} else {
							result.completeExceptionally(innerException);
						}
					} else {
						result.complete(innerResult);
					}
				});
			}
		});

		return result;
	}

	@NotNull
	public static CompletableFuture<Void> runAsync(@NotNull Runnable runnable) {
		return CompletableFuture.runAsync(ExecutionContext.wrap(runnable));
	}

	@NotNull
	public static CompletableFuture<Void> runAsync(@NotNull Runnable runnable, @NotNull Executor executor) {
		return CompletableFuture.runAsync(ExecutionContext.wrap(runnable), executor);
	}

	@NotNull
	public static CompletableFuture<Void> runAsync(@NotNull Supplier<? extends CompletableFuture<Void>> asyncRunnable) {
		return unwrap(supply(asyncRunnable));
	}

	@NotNull
	public static CompletableFuture<Void> runAsync(@NotNull Supplier<? extends CompletableFuture<Void>> asyncRunnable, @NotNull Executor executor) {
		return unwrap(supply(asyncRunnable, executor));
	}

	@NotNull
	public static <T> CompletableFuture<T> supply(@NotNull Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(ExecutionContext.wrap(supplier));
	}

	@NotNull
	public static <T> CompletableFuture<T> supply(@NotNull Supplier<T> supplier, @NotNull Executor executor) {
		return CompletableFuture.supplyAsync(ExecutionContext.wrap(supplier), executor);
	}

	@NotNull
	public static <T> CompletableFuture<T> supplyAsync(@NotNull Supplier<? extends CompletableFuture<T>> supplier) {
		return unwrap(supply(supplier));
	}

	@NotNull
	public static <T> CompletableFuture<T> supplyAsync(@NotNull Supplier<? extends CompletableFuture<T>> supplier, @NotNull Executor executor) {
		return unwrap(supply(supplier, executor));
	}
}
