// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
	protected static final int ASYNC_DELAY = 500;
	protected static final TimeUnit ASYNC_DELAY_UNIT = TimeUnit.MILLISECONDS;

	protected static final int TEST_TIMEOUT = 1000;
	protected static final TimeUnit TEST_TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

	private CompletableFuture<?> timeoutFutureSource = Async.delayAsync(TEST_TIMEOUT, TEST_TIMEOUT_UNIT);

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

//        /// <summary>
//        /// The maximum length of time to wait for something that we do not expect will happen
//        /// within the timeout.
//        /// </summary>
//        protected static readonly TimeSpan ExpectedTimeout = TimeSpan.FromSeconds(2);
//
//        private const int GCAllocationAttempts = 10;
//
//        protected TestBase(ITestOutputHelper logger)
//        {
//            this.Logger = logger;
//        }

	/**
	 * Gets the source of {@link #getTimeoutFuture()} that influences when tests consider themselves to be timed out.
	 */
	@NotNull
	protected final CompletableFuture<?> getTimeoutFutureSource() {
		return timeoutFutureSource;
	}

	/**
	 * Sets the source of {@link #getTimeoutFuture()} that influences when tests consider themselves to be timed out.
	 */
	protected final void setTimeoutFutureSource(@NotNull CompletableFuture<?> value) {
		timeoutFutureSource = value;
	}

	/**
	 * Gets a {@link CompletableFuture} that is canceled when the test times out, per the policy set by
	 * {@link #getTimeoutFutureSource()}.
	 */
	@NotNull
	protected final CompletableFuture<?> getTimeoutFuture() {
		CompletableFuture<Object> timeoutFuture = new CompletableFuture<>();
		getTimeoutFutureSource().handle((result, exception) -> timeoutFuture.cancel(true));
		return timeoutFuture;
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

//        /// <summary>
//        /// Runs a given scenario many times to observe memory characteristics and assert that they can satisfy given conditions.
//        /// </summary>
//        /// <param name="scenario">The delegate to invoke.</param>
//        /// <param name="maxBytesAllocated">The maximum number of bytes allowed to be allocated by one run of the scenario. Use -1 to indicate no limit.</param>
//        /// <param name="iterations">The number of times to invoke <paramref name="scenario"/> in a row before measuring average memory impact.</param>
//        /// <param name="allowedAttempts">The number of times the (scenario * iterations) loop repeats with a failing result before ultimately giving up.</param>
//        protected void CheckGCPressure(Action scenario, int maxBytesAllocated, int iterations = 100, int allowedAttempts = GCAllocationAttempts)
//        {
//            // prime the pump
//            for (int i = 0; i < iterations; i++)
//            {
//                scenario();
//            }
//
//            // This test is rather rough.  So we're willing to try it a few times in order to observe the desired value.
//            bool passingAttemptObserved = false;
//            for (int attempt = 1; attempt <= allowedAttempts; attempt++)
//            {
//                this.Logger?.WriteLine("Iteration {0}", attempt);
//                long initialMemory = GC.GetTotalMemory(true);
//                for (int i = 0; i < iterations; i++)
//                {
//                    scenario();
//                }
//
//                long allocated = (GC.GetTotalMemory(false) - initialMemory) / iterations;
//
//                // If there is a dispatcher sync context, let it run for a bit.
//                // This allows any posted messages that are now obsolete to be released.
//                if (SingleThreadedSynchronizationContext.IsSingleThreadedSyncContext(SynchronizationContext.Current))
//                {
//                    var frame = SingleThreadedSynchronizationContext.NewFrame();
//                    SynchronizationContext.Current.Post(state => frame.Continue = false, null);
//                    SingleThreadedSynchronizationContext.PushFrame(SynchronizationContext.Current, frame);
//                }
//
//                long leaked = (GC.GetTotalMemory(true) - initialMemory) / iterations;
//
//                this.Logger?.WriteLine("{0} bytes leaked per iteration.", leaked);
//                this.Logger?.WriteLine("{0} bytes allocated per iteration ({1} allowed).", allocated, maxBytesAllocated);
//
//                if (leaked <= 0 && (maxBytesAllocated == -1 || allocated <= maxBytesAllocated))
//                {
//                    passingAttemptObserved = true;
//                }
//
//                if (!passingAttemptObserved)
//                {
//                    // give the system a bit of cool down time to increase the odds we'll pass next time.
//                    GC.Collect();
//                    Thread.Sleep(250);
//                }
//            }
//
//            Assert.True(passingAttemptObserved);
//        }
//
//        /// <summary>
//        /// Runs a given scenario many times to observe memory characteristics and assert that they can satisfy given conditions.
//        /// </summary>
//        /// <param name="scenario">The delegate to invoke.</param>
//        /// <param name="maxBytesAllocated">The maximum number of bytes allowed to be allocated by one run of the scenario. Use -1 to indicate no limit.</param>
//        /// <param name="iterations">The number of times to invoke <paramref name="scenario"/> in a row before measuring average memory impact.</param>
//        /// <param name="allowedAttempts">The number of times the (scenario * iterations) loop repeats with a failing result before ultimately giving up.</param>
//        /// <returns>A task that captures the result of the operation.</returns>
//        protected async Task CheckGCPressureAsync(Func<Task> scenario, int maxBytesAllocated, int iterations = 100, int allowedAttempts = GCAllocationAttempts)
//        {
//            // prime the pump
//            for (int i = 0; i < iterations; i++)
//            {
//                await scenario();
//            }
//
//            // This test is rather rough.  So we're willing to try it a few times in order to observe the desired value.
//            bool passingAttemptObserved = false;
//            for (int attempt = 1; attempt <= allowedAttempts; attempt++)
//            {
//                this.Logger?.WriteLine("Iteration {0}", attempt);
//                long initialMemory = GC.GetTotalMemory(true);
//                for (int i = 0; i < iterations; i++)
//                {
//                    await scenario();
//                }
//
//                long allocated = (GC.GetTotalMemory(false) - initialMemory) / iterations;
//
//                // Allow the message queue to drain.
//                await Task.Yield();
//
//                long leaked = (GC.GetTotalMemory(true) - initialMemory) / iterations;
//
//                this.Logger?.WriteLine("{0} bytes leaked per iteration.", leaked);
//                this.Logger?.WriteLine("{0} bytes allocated per iteration ({1} allowed).", allocated, maxBytesAllocated);
//
//                if (leaked <= 0 && (maxBytesAllocated == -1 || allocated <= maxBytesAllocated))
//                {
//                    passingAttemptObserved = true;
//                }
//
//                if (!passingAttemptObserved)
//                {
//                    // give the system a bit of cool down time to increase the odds we'll pass next time.
//                    GC.Collect();
//                    Thread.Sleep(250);
//                }
//            }
//
//            Assert.True(passingAttemptObserved);
//        }
//
//        protected void CheckGCPressure(Func<Task> scenario, int maxBytesAllocated, int iterations = 100, int allowedAttempts = GCAllocationAttempts)
//        {
//            this.ExecuteOnDispatcher(() => this.CheckGCPressureAsync(scenario, maxBytesAllocated));
//        }
//
//        /// <summary>
//        /// Executes the delegate on a thread with <see cref="ApartmentState.STA"/>
//        /// and without a current <see cref="SynchronizationContext"/>.
//        /// </summary>
//        /// <param name="action">The delegate to execute.</param>
//        protected void ExecuteOnSTA(Action action)
//        {
//            Requires.NotNull(action, nameof(action));
//
//            if (Thread.CurrentThread.GetApartmentState() == ApartmentState.STA
//                && SynchronizationContext.Current == null)
//            {
//                action();
//                return;
//            }
//
//            Exception staFailure = null;
//            var staThread = new Thread(state =>
//            {
//                try
//                {
//                    action();
//                }
//                catch (Exception ex)
//                {
//                    staFailure = ex;
//                }
//            });
//            staThread.SetApartmentState(ApartmentState.STA);
//            staThread.Start();
//            staThread.Join();
//            if (staFailure != null)
//            {
//                ExceptionDispatchInfo.Capture(staFailure).Throw(); // rethrow preserving callstack.
//            }
//        }
//
//        protected void ExecuteOnDispatcher(Action action)
//        {
//            this.ExecuteOnDispatcher(delegate
//            {
//                action();
//                return TplExtensions.CompletedTask;
//            });
//        }
//
//        protected void ExecuteOnDispatcher(Func<Task> action)
//        {
//            Action worker = delegate
//            {
//                var frame = SingleThreadedSynchronizationContext.NewFrame();
//                Exception failure = null;
//                SynchronizationContext.Current.Post(
//                    async _ =>
//                    {
//                        try
//                        {
//                            await action();
//                        }
//                        catch (Exception ex)
//                        {
//                            failure = ex;
//                        }
//                        finally
//                        {
//                            frame.Continue = false;
//                        }
//                    },
//                    null);
//
//                SingleThreadedSynchronizationContext.PushFrame(SynchronizationContext.Current, frame);
//                if (failure != null)
//                {
//                    ExceptionDispatchInfo.Capture(failure).Throw();
//                }
//            };
//
//            if (Thread.CurrentThread.GetApartmentState() == ApartmentState.STA &&
//                SingleThreadedSynchronizationContext.IsSingleThreadedSyncContext(SynchronizationContext.Current))
//            {
//                worker();
//            }
//            else
//            {
//                this.ExecuteOnSTA(() =>
//                {
//                    if (!SingleThreadedSynchronizationContext.IsSingleThreadedSyncContext(SynchronizationContext.Current))
//                    {
//                        SynchronizationContext.SetSynchronizationContext(SingleThreadedSynchronizationContext.New());
//                    }
//
//                    worker();
//                });
//            }
//        }
}
