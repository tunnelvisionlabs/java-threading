// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
	public static <T> CompletableFuture<T> awaitAsync(@NotNull Awaitable<? extends T> awaitable) {
		return awaitAsync(awaitable, AsyncFunctions.identity());
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull Awaitable<? extends T> awaitable, @NotNull Function<? super T, ? extends CompletableFuture<U>> continuation) {
		Awaiter<? extends T> awaiter = awaitable.getAwaiter();
		if (awaiter.isDone()) {
			try {
				continuation.apply(awaiter.getResult());
			} catch (Throwable ex) {
				return Futures.fromException(ex);
			}
		}

		final Function<? super T, ? extends CompletableFuture<U>> flowContinuation = ExecutionContext.wrap(continuation);

		Executor executor = command -> awaiter.onCompleted(command);
		return CompletableFuture
			.supplyAsync(() -> flowContinuation.apply(awaiter.getResult()), executor)
			.thenCompose(AsyncFunctions.<CompletableFuture<U>>unwrap());
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull Awaitable<?> awaitable, @NotNull Supplier<? extends CompletableFuture<U>> continuation) {
		return awaitAsync(awaitable, ignored -> continuation.get());
	}

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
	public static <U> CompletableFuture<U> usingAsync(@NotNull AutoCloseable resource, @NotNull Supplier<? extends CompletableFuture<U>> body) {
		return usingAsync(resource, r -> body.get());
	}

	@NotNull
	public static <T extends AutoCloseable, U> CompletableFuture<U> usingAsync(@NotNull T resource, @NotNull Function<? super T, ? extends CompletableFuture<U>> body) {
		CompletableFuture<U> evaluatedBody;
		try {
			evaluatedBody = body.apply(resource);
		} catch (Throwable ex) {
			evaluatedBody = Futures.fromException(ex);
		}

		return finallyAsync(
			evaluatedBody,
			() -> {
				try {
					resource.close();
				} catch (CompletionException | CancellationException ex) {
					throw ex;
				} catch (Throwable ex) {
					throw new CompletionException(ex);
				}
			});
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
	public static Awaitable<Void> yieldAsync() {
		return YieldAwaitable.INSTANCE;
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

	@NotNull
	public static CompletableFuture<CompletableFuture<?>> whenAny(@NotNull CompletableFuture<?>... futures) {
		return CompletableFuture.anyOf(futures).handle(
			(result, exception) -> {
				for (CompletableFuture<?> future : futures) {
					if (future.isDone()) {
						return future;
					}
				}

				throw new IllegalStateException("Expected at least one future to be complete.");
			});
	}

	private static final class YieldAwaitable implements Awaitable<Void> {
		public static final YieldAwaitable INSTANCE = new YieldAwaitable();

		@Override
		public Awaiter<Void> getAwaiter() {
			return new YieldAwaiter();
		}
	}

	private static final class YieldAwaiter implements Awaiter<Void> {
		@Override
		public boolean isDone() {
			// yielding is always required for YieldAwaiter, hence false
			return false;
		}

		@Override
		public Void getResult() {
			return null;
		}

		@Override
		public void onCompleted(@NotNull Runnable continuation) {
			Requires.notNull(continuation, "continuation");

			SynchronizationContext synchronizationContext = SynchronizationContext.getCurrent();
			if (synchronizationContext != null && synchronizationContext.getClass() != SynchronizationContext.class) {
				synchronizationContext.execute(continuation);
			}

			ThreadPool.commonPool().execute(continuation);
		}
	}
}
