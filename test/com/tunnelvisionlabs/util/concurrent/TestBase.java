// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.SingleThreadedSynchronizationContext.Frame;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;

/**
 * Copied from Microsoft/vs-threading@14f77875.
 */
public abstract class TestBase {
	private static final boolean DEBUG = false;

	protected static final int ASYNC_DELAY = 500;
	protected static final TimeUnit ASYNC_DELAY_UNIT = TimeUnit.MILLISECONDS;

	protected static final int TEST_TIMEOUT = 1000;
	protected static final TimeUnit TEST_TIMEOUT_UNIT = DEBUG ? TimeUnit.MINUTES : TimeUnit.MILLISECONDS;

	private CancellationTokenSource timeoutTokenSource = new CancellationTokenSource(Duration.ofMillis(TEST_TIMEOUT_UNIT.toMillis(TEST_TIMEOUT)));

	@Rule
	public final ExpectedException thrown = ExpectedException.none();

	@Rule
	public final Timeout testTimeout = new Timeout(TEST_TIMEOUT * 5, TEST_TIMEOUT_UNIT);

	private SynchronizationContext synchronizationContext;
	private CallContext callContext;

	@Before
	public final void initState() {
		synchronizationContext = SynchronizationContext.getCurrent();
		callContext = CallContext.getCurrent();
		Assert.assertFalse(ExecutionContext.isFlowSuppressed());
	}

	@After
	public final void cleanState() {
		CallContext.setCallContext(callContext);
		SynchronizationContext.setSynchronizationContext(synchronizationContext);
	}

	/**
	 * The maximum length of time to wait for something that we expect will happen within the timeout.
	 */
	protected static final long UNEXPECTED_TIMEOUT = 5000;
	protected static final TimeUnit UNEXPECTED_TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

	/**
	 * The maximum length of time to wait for something that we do not expect will happen within the timeout.
	 */
	protected static final long EXPECTED_TIMEOUT = 2;
	protected static final TimeUnit EXPECTED_TIMEOUT_UNIT = TimeUnit.SECONDS;

	private static final int GC_ALLOCATION_ATTEMPTS = 10;

//        protected TestBase(ITestOutputHelper logger)
//        {
//            this.Logger = logger;
//        }

	/**
	 * Gets the source of {@link #getTimeoutFuture()} that influences when tests consider themselves to be timed out.
	 */
	@NotNull
	protected final CancellationTokenSource getTimeoutTokenSource() {
		return timeoutTokenSource;
	}

	/**
	 * Sets the source of {@link #getTimeoutFuture()} that influences when tests consider themselves to be timed out.
	 */
	protected final void setTimeoutTokenSource(@NotNull CancellationTokenSource value) {
		timeoutTokenSource = value;
	}

	/**
	 * Gets a {@link CancellationToken} that is canceled when the test times out, per the policy set by
	 * {@link #getTimeoutTokenSource()}.
	 */
	@NotNull
	protected final CancellationToken getTimeoutToken() {
		return getTimeoutTokenSource().getToken();
	}

//        /// <summary>
//        /// Gets or sets the logger to use for writing text to be captured in the test results.
//        /// </summary>
//        protected ITestOutputHelper Logger { get; set; }

	/**
	 * Verifies that continuations scheduled on a future will not be executed inline with the specified completing
	 * action.
	 *
	 * @param antecedent The future to test.
	 * @param completingAction The action that results in the synchronous completion of the future.
	 */
	protected static void verifyDoesNotInlineContinuations(@NotNull CompletableFuture<?> antecedent, @NotNull Runnable completingAction) {
		Requires.notNull(antecedent, "antecedent");
		Requires.notNull(completingAction, "completingAction");

		CompletableFuture<Void> completingActionFinished = new CompletableFuture<>();
		CompletableFuture<Void> continuation = antecedent.handle((result, exception) -> {
			try {
				return completingActionFinished.get(ASYNC_DELAY, ASYNC_DELAY_UNIT);
			} catch (InterruptedException | ExecutionException | TimeoutException ex) {
				throw new CompletionException(ex);
			}
		});

		completingAction.run();
		completingActionFinished.complete(null);

		// Rethrow the exception if it turned out it deadlocked.
		continuation.join();
	}

	/**
	 * Verifies that continuations scheduled on a future can be executed inline with the specified completing action.
	 *
	 * @param antecedent The future to test.
	 * @param completingAction The action that results in the synchronous completion of the future.
	 */
	protected static void verifyCanInlineContinuations(@NotNull CompletableFuture<?> antecedent, @NotNull Runnable completingAction) {
		Requires.notNull(antecedent, "antecedent");
		Requires.notNull(completingAction, "completingAction");

		Thread callingThread = Thread.currentThread();
		CompletableFuture<Void> continuation = antecedent.handle((result, exception) -> {
			Assert.assertSame(callingThread, Thread.currentThread());
			return null;
		});

		completingAction.run();
		Assert.assertTrue(continuation.isDone());

		// Rethrow any exceptions.
		continuation.join();
	}

	protected final void checkGCPressure(@NotNull Runnable scenario, int maxBytesAllocated) {
		checkGCPressure(scenario, maxBytesAllocated, 100, GC_ALLOCATION_ATTEMPTS);
	}

	protected final void checkGCPressure(@NotNull Runnable scenario, int maxBytesAllocated, int iterations) {
		checkGCPressure(scenario, maxBytesAllocated, iterations, GC_ALLOCATION_ATTEMPTS);
	}

	/// <summary>
	/// Runs a given scenario many times to observe memory characteristics and assert that they can satisfy given conditions.
	/// </summary>
	/// <param name="scenario">The delegate to invoke.</param>
	/// <param name="maxBytesAllocated">The maximum number of bytes allowed to be allocated by one run of the scenario. Use -1 to indicate no limit.</param>
	/// <param name="iterations">The number of times to invoke <paramref name="scenario"/> in a row before measuring average memory impact.</param>
	/// <param name="allowedAttempts">The number of times the (scenario * iterations) loop repeats with a failing result before ultimately giving up.</param>
	protected final void checkGCPressure(@NotNull Runnable scenario, int maxBytesAllocated, int iterations, int allowedAttempts) {
		// prime the pump
		for (int i = 0; i < iterations; i++) {
			scenario.run();
		}

		// This test is rather rough.  So we're willing to try it a few times in order to observe the desired value.
		boolean passingAttemptObserved = false;
		for (int attempt = 1; attempt <= allowedAttempts; attempt++) {
			System.out.format("Iteration %s%n", attempt);
			long initialMemory = getTotalMemory(true);
			for (int i = 0; i < iterations; i++) {
				scenario.run();
			}

			long allocated = (getTotalMemory(false) - initialMemory) / iterations;

			// If there is a dispatcher sync context, let it run for a bit.
			// This allows any posted messages that are now obsolete to be released.
			if (SingleThreadedSynchronizationContext.isSingleThreadedSyncContext(SynchronizationContext.getCurrent())) {
				Frame frame = SingleThreadedSynchronizationContext.newFrame();
				SynchronizationContext.getCurrent().post(state -> frame.setContinue(false), null);
				SingleThreadedSynchronizationContext.pushFrame(SynchronizationContext.getCurrent(), frame);
			}

			long leaked = (getTotalMemory(true) - initialMemory) / iterations;

			System.out.format("%s bytes leaked per iteration.%n", leaked);
			System.out.format("%s bytes allocated per iteration (%s allowed).%n", allocated, maxBytesAllocated);

			if (leaked <= 0 && (maxBytesAllocated == -1 || allocated <= maxBytesAllocated)) {
				passingAttemptObserved = true;
			}

			if (!passingAttemptObserved) {
				// give the system a bit of cool down time to increase the odds we'll pass next time.
				Runtime.getRuntime().gc();
				try {
					Thread.sleep(250);
				} catch (InterruptedException ex) {
				}
			}
		}

		Assert.assertTrue(passingAttemptObserved);
	}

	/// <summary>
	/// Runs a given scenario many times to observe memory characteristics and assert that they can satisfy given conditions.
	/// </summary>
	/// <param name="scenario">The delegate to invoke.</param>
	/// <param name="maxBytesAllocated">The maximum number of bytes allowed to be allocated by one run of the scenario. Use -1 to indicate no limit.</param>
	/// <param name="iterations">The number of times to invoke <paramref name="scenario"/> in a row before measuring average memory impact.</param>
	/// <param name="allowedAttempts">The number of times the (scenario * iterations) loop repeats with a failing result before ultimately giving up.</param>
	/// <returns>A task that captures the result of the operation.</returns>
	protected final CompletableFuture<Void> checkGCPressureAsync(@NotNull Supplier<? extends CompletableFuture<?>> scenario, int maxBytesAllocated, int iterations, int allowedAttempts) {
		return Async.awaitAsync(
			// prime the pump
			Async.forAsync(
				() -> 0,
				i -> i < iterations,
				i -> i + 1,
				() -> scenario.get()),
			() -> {
				// This test is rather rough.  So we're willing to try it a few times in order to observe the desired value.
				AtomicBoolean passingAttemptObserved = new AtomicBoolean(false);
				return Async.awaitAsync(
					Async.forAsync(
						() -> 1,
						attempt -> attempt <= allowedAttempts,
						attempt -> attempt + 1,
						attempt -> {
							System.out.println("Iteration " + attempt);
							long initialMemory = getTotalMemory(true);

							return Async.awaitAsync(
								Async.forAsync(
									() -> 0,
									i -> i < iterations,
									i -> i + 1,
									() -> scenario.get()),
								() -> {
									long allocated = (getTotalMemory(false) - initialMemory) / iterations;

									// Allow the message queue to drain.
									return Async.awaitAsync(
										Async.yieldAsync(),
										() -> {
											long leaked = (getTotalMemory(true) - initialMemory) / iterations;

											System.out.format("%s bytes leaked per iteration.%n", leaked);
											System.out.format("%s bytes allocated per iteration (%s allowed).%n", allocated, maxBytesAllocated);

											if (leaked <= 0 && (maxBytesAllocated == -1 || allocated <= maxBytesAllocated)) {
												passingAttemptObserved.set(true);
											}

											if (!passingAttemptObserved.get()) {
												// give the system a bit of cool down time to increase the odds we'll pass next time.
												System.gc();
												try {
													Thread.sleep(250);
												} catch (InterruptedException ex) {
												}
											}

											return Futures.completedNull();
										});
								});
						}),
					() -> {
						Assert.assertTrue(passingAttemptObserved.get());
						return Futures.completedNull();
					});
			});
	}

	private static long getTotalMemory(boolean forceFullCollection) {
		Runtime runtime = Runtime.getRuntime();
		if (forceFullCollection) {
			runtime.gc();
		}

		return runtime.totalMemory() - runtime.freeMemory();
	}

	protected final void checkGCPressure(@NotNull Supplier<? extends CompletableFuture<?>> scenario, int maxBytesAllocated) {
		checkGCPressure(scenario, maxBytesAllocated, 100, GC_ALLOCATION_ATTEMPTS);
	}

	protected final void checkGCPressure(@NotNull Supplier<? extends CompletableFuture<?>> scenario, int maxBytesAllocated, int iterations) {
		checkGCPressure(scenario, maxBytesAllocated, iterations, GC_ALLOCATION_ATTEMPTS);
	}

	protected final void checkGCPressure(@NotNull Supplier<? extends CompletableFuture<?>> scenario, int maxBytesAllocated, int iterations, int allowedAttempts) {
		this.executeOnDispatcher(() -> this.checkGCPressureAsync(scenario, maxBytesAllocated, iterations, allowedAttempts));
	}

	/**
	 * Executes the action on a thread with <see cref="ApartmentState.STA"/> and without a current
	 * {@link SynchronizationContext}.
	 *
	 * @param action The action to execute.
	 */
	protected final void executeOnSTA(@NotNull Runnable action) {
		Requires.notNull(action, "action");

//		if (//Thread.CurrentThread.GetApartmentState() == ApartmentState.STA &&
//			SynchronizationContext.getCurrent() == null) {
//			action.run();
//			return;
//		}

		StrongBox<Throwable> staFailure = new StrongBox<>();
		Thread staThread = new Thread(()
			-> {
			try {
				action.run();
			} catch (Throwable ex) {
				staFailure.set(ex);
			}
		});
//            staThread.SetApartmentState(ApartmentState.STA);
		staThread.start();

		boolean interrupted = false;
		while (true) {
			try {
				staThread.join();
				break;
			} catch (InterruptedException ex) {
				interrupted = true;
			}
		}

		if (interrupted) {
			Thread.currentThread().interrupt();
		}

		if (staFailure.get() != null) {
			throw new CompletionException(staFailure.get());
		}
	}

	protected final void executeOnDispatcher(@NotNull Runnable action) {
		this.executeOnDispatcher(() -> {
			action.run();
			return Futures.completedNull();
		});
	}

	protected final void executeOnDispatcher(@NotNull Supplier<? extends CompletableFuture<?>> action) {
		Runnable worker = () -> {
			Frame frame = SingleThreadedSynchronizationContext.newFrame();
			StrongBox<Throwable> failure = new StrongBox<>();
			SynchronizationContext.getCurrent().post(
				ignored -> {
					Async.awaitAsync(action.get())
					.whenComplete((result, ex) -> {
						failure.set(ex);
						frame.setContinue(false);
					});
				},
				null);

			SingleThreadedSynchronizationContext.pushFrame(SynchronizationContext.getCurrent(), frame);
			if (failure.get() != null) {
				throw new CompletionException(failure.get());
			}
		};

		if (//Thread.CurrentThread.GetApartmentState() == ApartmentState.STA &&
			SingleThreadedSynchronizationContext.isSingleThreadedSyncContext(SynchronizationContext.getCurrent())) {
			worker.run();
		} else {
			this.executeOnSTA(() -> {
				if (!SingleThreadedSynchronizationContext.isSingleThreadedSyncContext(SynchronizationContext.getCurrent())) {
					SynchronizationContext.setSynchronizationContext(SingleThreadedSynchronizationContext.create());
				}

				worker.run();
			});
		}
	}
}
