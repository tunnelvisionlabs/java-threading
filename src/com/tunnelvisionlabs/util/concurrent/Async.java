// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public enum Async {
	;

	private static final ScheduledThreadPoolExecutor DELAY_SCHEDULER = new ScheduledThreadPoolExecutor(1);

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter) {
		return awaitAsync(awaiter, AsyncFunctions.identity(), true);
	}

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter, boolean continueOnCapturedContext) {
		return awaitAsync(awaiter, AsyncFunctions.identity(), continueOnCapturedContext);
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter, @NotNull Function<? super T, ? extends CompletableFuture<U>> continuation) {
		return awaitAsync(awaiter, continuation, true);
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter, @NotNull Function<? super T, ? extends CompletableFuture<U>> continuation, boolean continueOnCapturedContext) {
		if (awaiter.isDone()) {
			return awaiter.thenCompose(continuation);
		}

		final Function<? super T, ? extends CompletableFuture<U>> flowContinuation = ExecutionContext.wrap(continuation);

		SynchronizationContext syncContext = continueOnCapturedContext ? SynchronizationContext.getCurrent() : null;
		Executor executor = syncContext != null ? syncContext : ForkJoinPool.commonPool();
		return awaiter.thenComposeAsync(result -> flowContinuation.apply(result), executor);
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<?> awaiter, @NotNull Supplier<? extends CompletableFuture<U>> continuation) {
		return awaitAsync(awaiter, continuation, true);
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<?> awaiter, @NotNull Supplier<? extends CompletableFuture<U>> continuation, boolean continueOnCapturedContext) {
		if (awaiter.isDone()) {
			return awaiter.thenCompose(result -> continuation.get());
		}

		SynchronizationContext syncContext = continueOnCapturedContext ? SynchronizationContext.getCurrent() : null;
		if (syncContext != null) {
			throw new UnsupportedOperationException("Not implemented");
		}

		final Supplier<? extends CompletableFuture<U>> flowContinuation = ExecutionContext.wrap(continuation);
		return awaiter.thenComposeAsync(result -> flowContinuation.get());
	}

	@NotNull
	public static CompletableFuture<Void> awaitAsync(@NotNull Executor executor) {
		return awaitAsync(executor, () -> Futures.completedNull());
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull Executor executor, @NotNull Supplier<? extends CompletableFuture<U>> continuation) {
		final Supplier<? extends CompletableFuture<U>> flowContinuation = ExecutionContext.wrap(continuation);
		return Futures.completedNull().thenComposeAsync(
			ignored -> flowContinuation.get(),
			executor);
	}

	@NotNull
	public static CompletableFuture<Void> delayAsync(long time, @NotNull TimeUnit unit) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		ScheduledFuture<?> scheduled = DELAY_SCHEDULER.schedule(
			ExecutionContext.wrap(() -> {
				ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> {
					result.complete(null);
				}));
			}),
			time,
			unit);

		// Unschedule if cancelled
		result.whenComplete((ignored, exception) -> {
			if (result.isCancelled()) {
				scheduled.cancel(true);
			}
		});

		return result;
	}

	@NotNull
	public static <T> CompletableFuture<T> finallyAsync(@NotNull CompletableFuture<T> future, @NotNull Runnable runnable) {
		// When both future and runnable throw an exception, the semantics of a finally block give precedence to the
		// exception thrown by the finally block. However, the implementation of CompletableFuture.whenComplete gives
		// precedence to the future.
		return future
			.handle((result, exception) -> {
				runnable.run();
				return future;
			})
			.thenCompose(Functions.identity());
	}

	@NotNull
	public static CompletableFuture<Void> forAsync(@NotNull Runnable initializer, @NotNull Supplier<? extends Boolean> condition, @NotNull Runnable increment, @NotNull Supplier<? extends CompletableFuture<?>> body) {
		try {
			initializer.run();
			return whileAsync(
				condition,
				() -> body.get().thenRun(increment));
		} catch (Throwable t) {
			return Futures.completedFailed(t);
		}
	}

	@NotNull
	public static <T> CompletableFuture<Void> forAsync(@NotNull Supplier<? extends T> initializer, @NotNull Predicate<? super T> condition, @NotNull Function<? super T, ? extends T> increment, @NotNull Function<? super T, ? extends CompletableFuture<?>> body) {
		AtomicReference<T> value = new AtomicReference<>();
		return forAsync(
			() -> value.set(initializer.get()),
			() -> condition.test(value.get()),
			() -> value.set(increment.apply(value.get())),
			() -> body.apply(value.get()));
	}

	@NotNull
	public static CompletableFuture<Void> whileAsync(@NotNull Supplier<? extends Boolean> predicate, @NotNull Supplier<? extends CompletableFuture<?>> body) {
		try {
			if (!predicate.get()) {
				return Futures.completedNull();
			}

			final ConcurrentLinkedQueue<Supplier<CompletableFuture<?>>> futures = new ConcurrentLinkedQueue<>();
			final AtomicReference<Supplier<CompletableFuture<?>>> evaluateBody = new AtomicReference<>();
			evaluateBody.set(() -> {
				CompletableFuture<?> bodyResult = body.get();
				return bodyResult.thenRun(() -> {
					if (predicate.get()) {
						futures.add(evaluateBody.get());
					}
				});
			});

			futures.add(evaluateBody.get());
			return whileImplAsync(futures);
		} catch (Throwable ex) {
			return Futures.completedFailed(ex);
		}
	}

	@NotNull
	public static Executor yieldAsync() {
		SynchronizationContext synchronizationContext = SynchronizationContext.getCurrent();
		if (synchronizationContext != null) {
			throw new UnsupportedOperationException("Not implemented");
		}

		return ForkJoinPool.commonPool();
	}

	@NotNull
	private static CompletableFuture<Void> whileImplAsync(@NotNull ConcurrentLinkedQueue<Supplier<CompletableFuture<?>>> futures) {
		while (true) {
			Supplier<CompletableFuture<?>> next = futures.poll();
			if (next == null) {
				return Futures.completedNull();
			}

			CompletableFuture<?> future = next.get();
			if (!future.isDone()) {
				return awaitAsync(future, () -> whileImplAsync(futures));
			}
		}
	}
}
