// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public enum Async {
	;

	static final boolean REQUIRE_UNWRAP_FOR_COMPLETED_ANTECEDENT;

	static {
		// The behavior of CompletableFuture.thenCompose when the antecedent is already completed is not consistent
		// across Java 8 releases. We are only allowed to avoid a call to Futures.unwrap in this case if the current
		// runtime properly propagates cancellation of the continuation.
		CompletableFuture<Void> composed = Futures.completedNull().thenCompose(s -> Futures.completedCancelled());
		boolean behavedCorrectly = composed.isDone() && composed.isCancelled();
		REQUIRE_UNWRAP_FOR_COMPLETED_ANTECEDENT = !behavedCorrectly;
	}

	private static final ScheduledExecutorService DELAY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(
		(Runnable r) -> {
			Thread thread = Executors.defaultThreadFactory().newThread(r);
			thread.setName(thread.getName() + " delayAsync scheduler");
			return thread;
		});

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull Awaitable<? extends T> awaitable) {
		return awaitAsync(awaitable, AsyncFunctions.identity());
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull Awaitable<? extends T> awaitable, @NotNull Function<? super T, ? extends CompletableFuture<U>> continuation) {
		Awaiter<? extends T> awaiter = awaitable.getAwaiter();
		if (awaiter.isDone()) {
			try {
				return continuation.apply(awaiter.getResult());
			} catch (Throwable ex) {
				return Futures.fromException(ex);
			}
		}

		Executor executor;
		if (awaiter instanceof CriticalNotifyCompletion) {
			executor = ((CriticalNotifyCompletion)awaiter)::unsafeOnCompleted;
		} else {
			executor = awaiter::onCompleted;
		}

		final Supplier<? extends CompletableFuture<U>> flowContinuation = ExecutionContext.wrap(() -> continuation.apply(awaiter.getResult()));
		return Futures.supplyAsync(() -> flowContinuation.get(), executor);
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull Awaitable<?> awaitable, @NotNull Supplier<? extends CompletableFuture<U>> continuation) {
		return awaitAsync(awaitable, ignored -> continuation.get());
	}

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull CompletableFuture<? extends T> future) {
		return awaitAsync(future, AsyncFunctions.identity());
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<? extends T> future, @NotNull Function<? super T, ? extends CompletableFuture<U>> continuation) {
		if (!REQUIRE_UNWRAP_FOR_COMPLETED_ANTECEDENT && future.isDone()) {
			// When the antecedent is already complete, we don't need to use unwrap in order for cancellation to be
			// properly handled.
			return future.thenCompose(continuation);
		}

		Awaitable<? extends T> awaitable = new FutureAwaitable<>(future, true);
		return awaitAsync(awaitable, continuation);
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<?> future, @NotNull Supplier<? extends CompletableFuture<U>> continuation) {
		if (!REQUIRE_UNWRAP_FOR_COMPLETED_ANTECEDENT && future.isDone()) {
			// When the antecedent is already complete, we don't need to use unwrap in order for cancellation to be
			// properly handled.
			return future.thenCompose(result -> continuation.get());
		}

		return awaitAsync(new FutureAwaitable<>(future, true), continuation);
	}

	@NotNull
	public static CompletableFuture<Void> awaitAsync(@NotNull Executor executor) {
		return awaitAsync(AwaitExtensions.switchTo(executor), AsyncFunctions.identity());
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull Executor executor, @NotNull Supplier<? extends CompletableFuture<U>> continuation) {
		Function<Void, CompletableFuture<U>> function = ignored -> continuation.get();
		return awaitAsync(AwaitExtensions.switchTo(executor), function);
	}

	@NotNull
	public static <T> Awaitable<T> configureAwait(@NotNull CompletableFuture<? extends T> future, boolean continueOnCapturedContext) {
		return new FutureAwaitable<>(future, continueOnCapturedContext);
	}

	@NotNull
	public static <T> CompletableFuture<T> runAsync(@NotNull Supplier<? extends CompletableFuture<T>> supplier) {
		try {
			StrongBox<CompletableFuture<T>> result = new StrongBox<>();
			ExecutionContext.run(ExecutionContext.capture(), s -> result.value = s.get(), supplier);
			return result.value;
		} catch (Throwable ex) {
			return Futures.fromException(ex);
		}
	}

	@NotNull
	public static CompletableFuture<Void> delayAsync(@NotNull Duration duration) {
		return delayAsync(duration, CancellationToken.none());
	}

	@NotNull
	public static CompletableFuture<Void> delayAsync(@NotNull Duration duration, @NotNull CancellationToken cancellationToken) {
		if (cancellationToken.isCancellationRequested()) {
			return Futures.completedCancelled();
		}

		if (duration.isZero()) {
			return Futures.completedNull();
		}

		CompletableFuture<Void> result = new CompletableFuture<>();
		ScheduledFuture<?> scheduled = DELAY_SCHEDULER.schedule(
			ExecutionContext.wrap(() -> {
				ForkJoinPool.commonPool().execute(ExecutionContext.wrap(() -> {
					result.complete(null);
				}));
			}),
			duration.toMillis(),
			TimeUnit.MILLISECONDS);

		// Unschedule if cancelled
		result.whenComplete((ignored, exception) -> {
			if (result.isCancelled()) {
				scheduled.cancel(true);
			}
		});

		if (cancellationToken.canBeCancelled()) {
			CancellationTokenRegistration registration = cancellationToken.register(f -> f.cancel(true), result);
			result.whenComplete((ignored, exception) -> registration.close());
		}

		return result;
	}

	@NotNull
	public static <T> CompletableFuture<T> finallyAsync(@NotNull CompletableFuture<T> future, @NotNull Runnable runnable) {
		// When both future and runnable throw an exception, the semantics of a finally block give precedence to the
		// exception thrown by the finally block. However, the implementation of CompletableFuture.whenComplete gives
		// precedence to the future.
		return Futures.unwrap(
			future.handle((result, exception) -> {
				runnable.run();
				return future;
			}));
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
	public static <T> CompletableFuture<Void> forAsync(@NotNull Supplier<? extends T> initializer, @NotNull Predicate<? super T> condition, @NotNull Function<? super T, ? extends T> increment, @NotNull Supplier<? extends CompletableFuture<?>> body) {
		AtomicReference<T> value = new AtomicReference<>();
		return forAsync(
			initializer,
			condition,
			increment,
			ignored -> body.get());
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
			return YieldAwaiter.INSTANCE;
		}
	}

	private static final class YieldAwaiter implements Awaiter<Void>, CriticalNotifyCompletion {
		public static final YieldAwaiter INSTANCE = new YieldAwaiter();

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
			onCompletedImpl(continuation, true);
		}

		@Override
		public void unsafeOnCompleted(@NotNull Runnable continuation) {
			onCompletedImpl(continuation, false);
		}

		private void onCompletedImpl(@NotNull Runnable continuation, boolean useExecutionContext) {
			Requires.notNull(continuation, "continuation");

			Executor executor = ForkJoinPool.commonPool();
			SynchronizationContext synchronizationContext = SynchronizationContext.getCurrent();
			if (synchronizationContext != null && synchronizationContext.getClass() != SynchronizationContext.class) {
				executor = synchronizationContext;
			}

			Runnable wrappedContinuation = useExecutionContext ? ExecutionContext.wrap(continuation) : continuation;
			executor.execute(wrappedContinuation);
		}
	}
}
