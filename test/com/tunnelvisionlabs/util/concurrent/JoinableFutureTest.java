// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFutureContext.RevertRelevance;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.CoreMatchers.isA;

/**
 * Copied from Microsoft/vs-threading@14f77875.
 */
public class JoinableFutureTest extends JoinableFutureTestBase {
	@Test
	public void testRunFuncOfTaskSTA() {
		runFuncOfTaskHelper();
	}

	@Test
	public void testRunFuncOfTaskMTA() {
		Futures.runAsync(() -> runFuncOfTaskHelper()).join();
	}

	@Test
	public void testRunFuncOfTaskOfTSTA() {
		runFuncOfTaskOfTHelper();
	}

	@Test
	public void testRunFuncOfTaskOfTMTA() {
		Futures.runAsync(() -> runFuncOfTaskOfTHelper()).join();
	}

	@Test
	public void testLeaveAndReturnToSTA() {
		AtomicBoolean fullyCompleted = new AtomicBoolean(false);
		asyncPump.run(() -> {
			Assert.assertSame(originalThread, Thread.currentThread());

			return Async.awaitAsync(
				ForkJoinPool.commonPool(),
				() -> {
					Assert.assertNotSame(originalThread, Thread.currentThread());

					return Async.awaitAsync(
						asyncPump.switchToMainThreadAsync(),
						() -> {
							Assert.assertSame(originalThread, Thread.currentThread());
							fullyCompleted.set(true);
							return Futures.completedNull();
						});
				});
		});

		Assert.assertTrue(fullyCompleted.get());
	}

	@Test
	public void testSwitchToMainThreadDoesNotYieldWhenAlreadyOnMainThread() {
		Assert.assertTrue("Yield occurred even when already on UI thread.", asyncPump.switchToMainThreadAsync().getAwaiter().isDone());
	}

	@Test
	public void testSwitchToMainThreadYieldsWhenOffMainThread() {
		Futures
			.runAsync(
				() -> Assert.assertFalse(
					"Yield did not occur when off Main thread.",
					asyncPump.switchToMainThreadAsync().getAwaiter().isDone()))
			.join();
	}

	@Test
	public void testSwitchToMainThreadAsyncContributesToHangReportsAndCollections() throws Exception {
		CompletableFuture<?> mainThreadRequestPended = new CompletableFuture<>();
		StrongBox<Throwable> delegateFailure = new StrongBox<>();

		Futures.runAsync(() -> {
			Awaiter<Void> awaiter = asyncPump.switchToMainThreadAsync().getAwaiter();
			awaiter.onCompleted(() -> {
				try {
					Assert.assertSame(originalThread, Thread.currentThread());
				} catch (Throwable ex) {
					delegateFailure.set(ex);
				} finally {
					testFrame.setContinue(false);
				}
			});
			mainThreadRequestPended.complete(null);
		});

		mainThreadRequestPended.get(TEST_TIMEOUT, TEST_TIMEOUT_UNIT);

		// Verify here that pendingTasks includes one task.
		Assert.assertEquals(1, getPendingFuturesCount());
		Assert.assertEquals(1, Iterables.size(joinableCollection));

		// Now let the request proceed through.
		pushFrame();

		Assert.assertEquals(0, getPendingFuturesCount());
		Assert.assertEquals(0, Iterables.size(joinableCollection));

		if (delegateFailure.get() != null) {
			throw new CompletionException(delegateFailure.get());
		}
	}

	@Test
	public void testSwitchToMainThreadAsyncWithinCompleteFutureGetsNewFuture() {
		// For this test, the JoinableTaskFactory we use shouldn't have its own collection.
		// This is important for hitting the code path that was buggy before this test was written.
		joinableCollection = null;
		asyncPump = new DerivedJoinableFutureFactory(context);

		AsyncManualResetEvent outerTaskCompleted = new AsyncManualResetEvent();
		StrongBox<CompletableFuture<?>> innerTask = new StrongBox<>();
		asyncPump.runAsync(() -> {
			innerTask.set(Futures.runAsync(() -> {
				return Async.awaitAsync(
					outerTaskCompleted,
					() -> {
						// This thread transition runs within the context of a completed task.
						// In this transition, the JoinableTaskFactory should create a new, incompleted
						// task to represent the transition.
						// This is verified by our DerivedJoinableTaskFactory which will throw if
						// the task has already completed.
						return Async.awaitAsync(asyncPump.switchToMainThreadAsync());
					});
			}));

			return Futures.completedNull();
		});
		outerTaskCompleted.set();

		innerTask.get().whenComplete((result, exception) -> testFrame.setContinue(false));

		// Now let the request proceed through.
		pushFrame();

		// rethrow exceptions
		innerTask.get().join();
	}

	@Test
	public void testSwitchToMainThreadAsyncTwiceRemainsInJoinableCollection() throws Exception {
		((DerivedJoinableFutureFactory)this.asyncPump).AssumeConcurrentUse = true;
		CompletableFuture<Void> mainThreadRequestPended = new CompletableFuture<>();
		StrongBox<Throwable> delegateFailure = new StrongBox<>();

		Futures.runAsync(() -> {
			asyncPump.runAsync(() -> {
				Awaiter<Void> awaiter = asyncPump.switchToMainThreadAsync().getAwaiter();
				awaiter.onCompleted(
					() -> {
						try {
							Assert.assertSame(originalThread, Thread.currentThread());
						} catch (Throwable ex) {
							delegateFailure.set(ex);
						} finally {
							testFrame.setContinue(false);
						}
					});
				awaiter.onCompleted(
					() -> {
						try {
							Assert.assertSame(originalThread, Thread.currentThread());
						} catch (Throwable ex) {
							delegateFailure.set(ex);
						} finally {
							testFrame.setContinue(false);
						}
					});
				return Futures.completedNull();
			});
			mainThreadRequestPended.complete(null);
		});

		mainThreadRequestPended.get(TEST_TIMEOUT, TEST_TIMEOUT_UNIT);

		// Verify here that pendingTasks includes one task.
		Assert.assertEquals(1, ((DerivedJoinableFutureFactory)asyncPump).getTransitioningTasksCount());

		// Now let the request proceed through.
		pushFrame();
		// reset for next time
		testFrame.setContinue(true);

		// Verify here that pendingTasks includes one task.
		Assert.assertEquals(1, ((DerivedJoinableFutureFactory)asyncPump).getTransitioningTasksCount());

		// Now let the request proceed through.
		pushFrame();

		Assert.assertEquals(0, ((DerivedJoinableFutureFactory)asyncPump).getTransitioningTasksCount());

		if (delegateFailure.get() != null) {
			throw new CompletionException(delegateFailure.get());
		}
	}

	@Test
	public void testSwitchToMainThreadAsyncTransitionsCanSeeAsyncLocals() {
		CompletableFuture<?> mainThreadRequestPended = new CompletableFuture<>();
		StrongBox<Throwable> delegateFailure = new StrongBox<>();

		AsyncLocal<Object> asyncLocal = new AsyncLocal<>();
		Object asyncLocalValue = new Object();

		// The point of this test is to verify that the transitioning/transitioned
		// methods on the JoinableTaskFactory can see into the AsyncLocal<T>.Value
		// as defined in the context that is requesting the transition.
		// The ProjectLockService depends on this behavior to identify UI thread
		// requestors that hold a project lock, on both sides of the transition.
		((DerivedJoinableFutureFactory)asyncPump).TransitioningToMainThreadCallback =
			jt -> {
				Assert.assertSame(asyncLocalValue, asyncLocal.getValue());
			};
		((DerivedJoinableFutureFactory)asyncPump).TransitionedToMainThreadCallback =
			jt -> {
				Assert.assertSame(asyncLocalValue, asyncLocal.getValue());
			};

		Futures.runAsync(() -> {
			asyncLocal.setValue(asyncLocalValue);
			Awaiter<Void> awaiter = asyncPump.switchToMainThreadAsync().getAwaiter();
			awaiter.onCompleted(() -> {
				try {
					Assert.assertSame(originalThread, Thread.currentThread());
					Assert.assertSame(asyncLocalValue, asyncLocal.getValue());
				} catch (Throwable ex) {
					delegateFailure.set(ex);
				} finally {
					testFrame.setContinue(false);
				}
			});
			mainThreadRequestPended.complete(null);
		});

		mainThreadRequestPended.join();

		// Now let the request proceed through.
		pushFrame();

		if (delegateFailure.get() != null) {
			throw new CompletionException(delegateFailure.get());
		}
	}

	@Test
	public void testSwitchToMainThreadCancellable() throws Exception {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			CancellationTokenSource cts = new CancellationTokenSource(Duration.ofMillis(ASYNC_DELAY_UNIT.toMillis(ASYNC_DELAY)));
			return AsyncAssert.cancelsIncorrectlyAsync(() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync(cts.getToken())));
		});

		task.get(TEST_TIMEOUT * 3, TEST_TIMEOUT_UNIT);
	}

	@Test
	public void testSwitchToMainThreadCancellableWithinRun() {
		CancellationTokenSource endTestTokenSource = new CancellationTokenSource(Duration.ofMillis(ASYNC_DELAY_UNIT.toMillis(ASYNC_DELAY)));

		// If we find a way to fix unwrap's handling of cancellation, this will change.
		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(CancellationException.class));
		asyncPump.run(() -> Async.usingAsync(
			context.suppressRelevance(),
			() -> Futures.runAsync(() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync(endTestTokenSource.getToken())))));
	}

	/**
	 * Verify that if the {@link JoinableFutureContext} was initialized without a {@link SynchronizationContext} whose
	 * {@link SynchronizationContext#post} method executes its delegate on the {@link Thread} passed to the
	 * {@link JoinableFutureContext} constructor, that an attempt to switch to the main thread using
	 * {@link JoinableFutureFactory} throws an informative exception.
	 */
	@Test
	public void testSwitchToMainThreadThrowsUsefulExceptionIfJFCIsMisconfigured()
	{
		SynchronizationContext.setSynchronizationContext(new SynchronizationContext());
		JoinableFutureContext jtc = new JoinableFutureContext();
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			ForkJoinPool.commonPool(),
			() -> Async.awaitAsync(
				AsyncAssert.throwsAsync(
					JoinableFutureContextException.class,
					() -> Async.awaitAsync(jtc.getFactory().switchToMainThreadAsync()))));

		asyncTest.join();
	}

	@Test
	public void testSwitchToSTADoesNotCauseUnrelatedReentrancy() throws Exception {
		CompletableFuture<Object> uiThreadNowBusy = new CompletableFuture<>();
		AtomicBoolean contenderHasReachedUIThread = new AtomicBoolean(false);

		CompletableFuture<Void> backgroundContender = Futures.runAsync(() -> Async.awaitAsync(
			uiThreadNowBusy,
			() -> Async.awaitAsync(
				asyncPump.switchToMainThreadAsync(),
				() -> {
					Assert.assertSame(originalThread, Thread.currentThread());
					contenderHasReachedUIThread.set(true);
					testFrame.setContinue(false);
					return Futures.completedNull();
				})));

		asyncPump.run(() -> {
			uiThreadNowBusy.complete(null);
			Assert.assertSame(originalThread, Thread.currentThread());

			return Async.awaitAsync(
				ForkJoinPool.commonPool(),
				() -> {
					Assert.assertNotSame(originalThread, Thread.currentThread());
					return Async.awaitAsync(
						// allow ample time for the background contender to re-enter the STA thread if it's possible (we don't want it to be).
						Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT),
						() -> Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(),
							() -> {
								Assert.assertSame(originalThread, Thread.currentThread());
								Assert.assertFalse("The contender managed to get to the STA thread while other work was on it.", contenderHasReachedUIThread.get());
								return Futures.completedNull();
							}));
				});
		});

		// Pump messages until everything's done.
		pushFrame();

		backgroundContender.get(ASYNC_DELAY, ASYNC_DELAY_UNIT);
	}

	@Test
	public void testSwitchToSTASucceedsForRelevantWork() {
		asyncPump.run(() -> {
			CompletableFuture<Void> backgroundContender = Futures.runAsync(() -> Async.awaitAsync(
				asyncPump.switchToMainThreadAsync(),
				() -> {
					Assert.assertSame(originalThread, Thread.currentThread());
					return Futures.completedNull();
				}));

			Assert.assertSame(originalThread, Thread.currentThread());

			return Async.awaitAsync(
				ForkJoinPool.commonPool(),
				() -> {
					Assert.assertNotSame(originalThread, Thread.currentThread());

					// We can't complete until this seemingly unrelated work completes.
					// This shouldn't deadlock because this synchronous operation kicked off
					// the operation to begin with.
					return Async.awaitAsync(
						backgroundContender,
						() -> Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(),
							() -> {
								Assert.assertSame(originalThread, Thread.currentThread());
								return Futures.completedNull();
							}));
				});
		});
	}

	@Test
	public void testSwitchToSTASucceedsForDependentWork() {
		CompletableFuture<Void> uiThreadNowBusy = new CompletableFuture<>();
		CompletableFuture<Void> backgroundContenderCompletedRelevantUIWork = new CompletableFuture<>();
		AsyncManualResetEvent backgroundInvitationReverted = new AsyncManualResetEvent();
		AtomicBoolean syncUIOperationCompleted = new AtomicBoolean(false);

		CompletableFuture<Void> backgroundContender = Futures.runAsync(() -> Async.awaitAsync(
			uiThreadNowBusy,
			() -> Async.awaitAsync(
				asyncPump.switchToMainThreadAsync(),
				() -> {
					Assert.assertSame(originalThread, Thread.currentThread());

					// Release, then reacquire the STA a couple of different ways
					// to verify that even after the invitation has been extended
					// to join the STA thread we can leave and revisit.
					return Async.awaitAsync(
						asyncPump.switchToMainThreadAsync(),
						() -> {
							Assert.assertSame(originalThread, Thread.currentThread());
							return Async.awaitAsync(
								Async.yieldAsync(),
								() -> {
									Assert.assertSame(originalThread, Thread.currentThread());

									// Now complete the task that the synchronous work is waiting before reverting their invitation.
									backgroundContenderCompletedRelevantUIWork.complete(null);

									// Temporarily get off UI thread until the UI thread has rescinded offer to lend its time.
									// In so doing, once the task we're waiting on has completed, we'll be scheduled to return using
									// the current synchronization context, which because we switched to the main thread earlier
									// and have not yet switched off, will mean our continuation won't execute until the UI thread
									// becomes available (without any reentrancy).
									return Async.awaitAsync(
										backgroundInvitationReverted,
										() -> {
											// We should now be on the UI thread (and the Run delegate below should have altogether completd.)
											Assert.assertSame(originalThread, Thread.currentThread());
											// should be true because continuation needs same thread that this is set on.
											Assert.assertTrue(syncUIOperationCompleted.get());
											return Futures.completedNull();
										});
								});
						});
				})));

		asyncPump.run(() -> {
			uiThreadNowBusy.complete(null);
			Assert.assertSame(this.originalThread, Thread.currentThread());

			return Async.awaitAsync(
				ForkJoinPool.commonPool(),
				() -> {
					Assert.assertNotSame(originalThread, Thread.currentThread());

					return Async.awaitAsync(
						Async.usingAsync(
							// invite the work to re-enter our synchronous work on the STA thread.
							joinableCollection.join(),
							// we can't complete until this seemingly unrelated work completes.
							// then stop inviting more work from background thread.
							() -> Async.awaitAsync(backgroundContenderCompletedRelevantUIWork)),
						() -> Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(),
							() -> {
								backgroundInvitationReverted.set();
								Assert.assertSame(originalThread, Thread.currentThread());
								syncUIOperationCompleted.set(true);

								return Async.usingAsync(
									joinableCollection.join(),
									// Since this background task finishes on the UI thread, we need to ensure
									// it can get on it.
									() -> Async.awaitAsync(backgroundContender));
							}));
				});
		});
	}

	@Test
	public void testTransitionToMainThreadNotRaisedWhenAlreadyOnMainThread() {
		DerivedJoinableFutureFactory factory = (DerivedJoinableFutureFactory)asyncPump;

		factory.run(() -> Async.awaitAsync(
			// Switch to main thread when we're already there.
			factory.switchToMainThreadAsync(),
			() -> {
				Assert.assertEquals("No transition expected since we're already on the main thread.", 0, factory.getTransitioningToMainThreadHitCount());
				Assert.assertEquals("No transition expected since we're already on the main thread.", 0, factory.getTransitionedToMainThreadHitCount());

				// While on the main thread, await something that executes on a background thread.
				return Async.awaitAsync(
					Futures.runAsync(() -> {
						Assert.assertEquals("No transition expected when moving off the main thread.", 0, factory.getTransitioningToMainThreadHitCount());
						Assert.assertEquals("No transition expected when moving off the main thread.", 0, factory.getTransitionedToMainThreadHitCount());
					}),
					() -> {
						Assert.assertEquals("No transition expected since the main thread was ultimately blocked for this job.", 0, factory.getTransitioningToMainThreadHitCount());
						Assert.assertEquals("No transition expected since the main thread was ultimately blocked for this job.", 0, factory.getTransitionedToMainThreadHitCount());

						// Now switch explicitly to a threadpool thread.
						return Async.awaitAsync(
							ForkJoinPool.commonPool(),
							() -> {
								Assert.assertEquals("No transition expected when moving off the main thread.", 0, factory.getTransitioningToMainThreadHitCount());
								Assert.assertEquals("No transition expected when moving off the main thread.", 0, factory.getTransitionedToMainThreadHitCount());

								// Now switch back to the main thread.
								return Async.awaitAsync(
									factory.switchToMainThreadAsync(),
									() -> {
										Assert.assertEquals("No transition expected because the main thread was ultimately blocked for this job.", 0, factory.getTransitioningToMainThreadHitCount());
										Assert.assertEquals("No transition expected because the main thread was ultimately blocked for this job.", 0, factory.getTransitionedToMainThreadHitCount());
										return Futures.completedNull();
									});
							});
					});
			}));
	}

	@Test
	@Category(FailsInCloudTest.class) // see https://github.com/Microsoft/vs-threading/issues/44
	public void testTransitionToMainThreadRaisedWhenSwitchingToMainThread() {
		DerivedJoinableFutureFactory factory = (DerivedJoinableFutureFactory)asyncPump;

		JoinableFuture<Void> joinableTask = factory.runAsync(() -> {
			// Switch to main thread when we're already there.
			return Async.awaitAsync(
				factory.switchToMainThreadAsync(),
				() -> {
					Assert.assertEquals("No transition expected since we're already on the main thread.", 0, factory.getTransitioningToMainThreadHitCount());
					Assert.assertEquals("No transition expected since we're already on the main thread.", 0, factory.getTransitionedToMainThreadHitCount());

					// While on the main thread, await something that executes on a background thread.
					return Async.awaitAsync(
						Futures.runAsync(() -> {
							Assert.assertEquals("No transition expected when moving off the main thread.", 0, factory.getTransitioningToMainThreadHitCount());
							Assert.assertEquals("No transition expected when moving off the main thread.", 0, factory.getTransitionedToMainThreadHitCount());
							return Async.delayAsync(5, TimeUnit.MILLISECONDS);
						}),
						() -> {
							Assert.assertEquals("Reacquisition of main thread should have raised transition events.", 1, factory.getTransitioningToMainThreadHitCount());
							Assert.assertEquals("Reacquisition of main thread should have raised transition events.", 1, factory.getTransitionedToMainThreadHitCount());

							// Now switch explicitly to a threadpool thread.
							return Async.awaitAsync(
								ForkJoinPool.commonPool(),
								() -> {
									Assert.assertEquals("No transition expected when moving off the main thread.", 1, factory.getTransitioningToMainThreadHitCount());
									Assert.assertEquals("No transition expected when moving off the main thread.", 1, factory.getTransitionedToMainThreadHitCount());

									// Now switch back to the main thread.
									return Async.awaitAsync(
										factory.switchToMainThreadAsync(),
										() -> {
											Assert.assertEquals("Reacquisition of main thread should have raised transition events.", 2, factory.getTransitioningToMainThreadHitCount());
											Assert.assertEquals("Reacquisition of main thread should have raised transition events.", 2, factory.getTransitionedToMainThreadHitCount());
											return Futures.completedNull();
										});
								});
						});
				});
		});

		// Simulate the UI thread just pumping ordinary messages
		joinableTask.getFuture().whenComplete((result, exception) -> testFrame.setContinue(false));
		pushFrame();
		// Throw exceptions thrown by the async task.
		joinableTask.join();
	}

	@Test
	public void testRunSynchronouslyNestedNoJoins() {
		AtomicBoolean outerCompleted = new AtomicBoolean();
		AtomicBoolean innerCompleted = new AtomicBoolean();
		asyncPump.run(() -> {
			Assert.assertSame(originalThread, Thread.currentThread());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertSame(originalThread, Thread.currentThread());

					return Async.awaitAsync(
						Futures.runAsync(() -> Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(),
							() -> {
								Assert.assertSame(originalThread, Thread.currentThread());
								return Futures.completedNull();
							})),
						() -> {
							asyncPump.run(() -> {
								Assert.assertSame(originalThread, Thread.currentThread());
								return Async.awaitAsync(
									Async.yieldAsync(),
									() -> {
										Assert.assertSame(originalThread, Thread.currentThread());

										return Async.awaitAsync(
											Futures.runAsync(() -> Async.awaitAsync(
												asyncPump.switchToMainThreadAsync(),
												() -> {
													Assert.assertSame(originalThread, Thread.currentThread());
													return Futures.completedNull();
												})),
											() -> {
												Assert.assertSame(originalThread, Thread.currentThread());
												innerCompleted.set(true);
												return Futures.completedNull();
											});
									});
							});

							return Async.awaitAsync(
								Async.yieldAsync(),
								() -> {
									Assert.assertSame(originalThread, Thread.currentThread());
									outerCompleted.set(true);
									return Futures.completedNull();
								});
						});
				});
		});

		Assert.assertTrue("Nested Run did not complete.", innerCompleted.get());
		Assert.assertTrue("Outer Run did not complete.", outerCompleted.get());
	}

	@Test
	public void testRunSynchronouslyNestedWithJoins() {
		AtomicBoolean outerCompleted = new AtomicBoolean(false);
		AtomicBoolean innerCompleted = new AtomicBoolean(false);

		asyncPump.run(() -> {
			Assert.assertSame(originalThread, Thread.currentThread());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertSame(originalThread, Thread.currentThread());

					return Async.awaitAsync(
						testReentrancyOfUnrelatedDependentWork(),
						() -> {
							Assert.assertSame(originalThread, Thread.currentThread());

							return Async.awaitAsync(
								Futures.runAsync(() -> Async.awaitAsync(
									asyncPump.switchToMainThreadAsync(),
									() -> {
										Assert.assertSame(originalThread, Thread.currentThread());
										return Futures.completedNull();
									})),
								() -> {
									return Async.awaitAsync(
										testReentrancyOfUnrelatedDependentWork(),
										() -> {
											Assert.assertSame(originalThread, Thread.currentThread());

											asyncPump.run(() -> {
												Assert.assertSame(originalThread, Thread.currentThread());
												return Async.awaitAsync(
													Async.yieldAsync(),
													() -> {
														Assert.assertSame(originalThread, Thread.currentThread());

														return Async.awaitAsync(
															testReentrancyOfUnrelatedDependentWork(),
															() -> {
																Assert.assertSame(originalThread, Thread.currentThread());

																return Async.awaitAsync(
																	Futures.runAsync(() -> Async.awaitAsync(
																		asyncPump.switchToMainThreadAsync(),
																		() -> {
																			Assert.assertSame(originalThread, Thread.currentThread());
																			return Futures.completedNull();
																		})),
																	() -> {
																		return Async.awaitAsync(
																			testReentrancyOfUnrelatedDependentWork(),
																			() -> {
																				Assert.assertSame(originalThread, Thread.currentThread());
																				innerCompleted.set(true);
																				return Futures.completedNull();
																			});
																	});
															});
													});
											});

											return Async.awaitAsync(
												Async.yieldAsync(),
												() -> {
													Assert.assertSame(originalThread, Thread.currentThread());
													outerCompleted.set(true);
													return Futures.completedNull();
												});
										});
								});
						});
				});
		});

		Assert.assertTrue("Nested Run did not complete.", innerCompleted.get());
		Assert.assertTrue("Outer Run did not complete.", outerCompleted.get());
	}

	@Test
	@Ignore("Fails for Java only")
	public void testRunSynchronouslyOffMainThreadRequiresJoinToReenterMainThreadForSameAsyncPumpInstance() {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			asyncPump.runAsync(() -> Async.awaitAsync(
				asyncPump.switchToMainThreadAsync(),
				() -> {
					Assert.assertSame("We're not on the Main thread!", originalThread, Thread.currentThread());
					return Futures.completedNull();
				}));
		});

		asyncPump.run(() -> {
			// Even though it's all the same instance of AsyncPump,
			// unrelated work (work not spun off from this block) must still be
			// Joined in order to execute here.
			return Async.awaitAsync(
				Async.whenAny(task, Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT)),
				completed -> {
					Assert.assertNotSame("The unrelated main thread work completed before the Main thread was joined.", task, completed);
					return Async.usingAsync(
						joinableCollection.join(),
						() -> {
							printActiveFuturesReport();
							return Async.awaitAsync(task);
						});
				});
		});
	}

	@Test
	public void testRunSynchronouslyOffMainThreadRequiresJoinToReenterMainThreadForDifferentAsyncPumpInstance() {
		JoinableFutureCollection otherCollection = this.context.createCollection();
		JoinableFutureFactory otherAsyncPump = this.context.createFactory(otherCollection);
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			otherAsyncPump.run(() -> Async.awaitAsync(
				otherAsyncPump.switchToMainThreadAsync(),
				() -> {
					Assert.assertSame(originalThread, Thread.currentThread());
					return Futures.completedNull();
				}));
		});

		asyncPump.run(() -> {
			return Async.awaitAsync(
				Async.whenAny(task, Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT)),
				completed -> {
					Assert.assertNotSame("The unrelated main thread work completed before the Main thread was joined.", task, completed);
					return Async.usingAsync(
						otherCollection.join(),
						() -> Async.awaitAsync(task));
				});
		});
	}

	/**
	 * Checks that posting to the {@link SynchronizationContext#getCurrent()} doesn't cause a hang.
	 */
	@Test
	public void testRunSwitchesToMainThreadAndPosts() {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			try {
				asyncPump.run(() -> Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> {
						SynchronizationContext current = SynchronizationContext.getCurrent();
						Assert.assertNotNull(current);
						current.post(s -> { }, null);
						return Futures.completedNull();
					}));
			} finally {
				testFrame.setContinue(false);
			}
		});

		// Now let the request proceed through.
		pushFrame();
		// rethrow exceptions.
		task.join();
	}

	/**
	 * Checks that posting to {@link SynchronizationContext#getCurrent()} doesn't cause a hang.
	 */
	@Test
	public void testRunSwitchesToMainThreadAndPostsTwice() {
		((DerivedJoinableFutureFactory)asyncPump).AssumeConcurrentUse = true;
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			try {
				asyncPump.run(() -> Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> {
						SynchronizationContext current = SynchronizationContext.getCurrent();
						Assert.assertNotNull(current);
						current.post(s -> {
						}, null);
						current.post(s -> {
						}, null);
						return Futures.completedNull();
					}));
			} finally {
				testFrame.setContinue(false);
			}
		});

		// Now let the request proceed through.
		pushFrame();
		// rethrow exceptions.
		task.join();
	}

	/**
	 * Checks that posting to {@link SynchronizationContext#getCurrent()} doesn't cause a hang.
	 */
	@Test
	public void testRunSwitchesToMainThreadAndPostsTwiceDoesNotImpactJoinableFutureCompletion() {
		((DerivedJoinableFutureFactory)this.asyncPump).AssumeConcurrentUse = true;
		StrongBox<CompletableFuture<Void>> task = new StrongBox<>();
		task.set(Futures.runAsync(() -> {
			try {
				asyncPump.run(() -> {
					return Async.awaitAsync(
						asyncPump.switchToMainThreadAsync(),
						() -> {
							SynchronizationContext current = SynchronizationContext.getCurrent();
							Assert.assertNotNull(current);

							// Kick off work that should *not* impact the completion of
							// the JoinableTask that lives within this Run delegate.
							// And enforce the assertion by blocking the main thread until
							// the JoinableTask is done, which would deadlock if the
							// JoinableTask were inappropriately blocking on the completion
							// of the posted message.
							current.post(s -> task.get().join(), null);

							// Post one more time, since an implementation detail may unblock
							// the JoinableTask for the very last posted message for reasons that
							// don't apply for other messages.
							current.post(s -> { }, null);

							return Futures.completedNull();
						});
				});
			} finally {
				testFrame.setContinue(false);
			}
		}));

		// Now let the request proceed through.
		pushFrame();
		// rethrow exceptions.
		task.get().join();
	}

	@Test
	public void testSwitchToMainThreadImmediatelyShouldNotHang() {
		((DerivedJoinableFutureFactory)this.asyncPump).AssumeConcurrentUse = true;

		AsyncManualResetEvent task1Started = new AsyncManualResetEvent(false);
		AsyncManualResetEvent task2Started = new AsyncManualResetEvent(false);

		asyncPump.run(() -> {
			CompletableFuture<Void> child1Task = Futures.runAsync(() -> {
				return Async.awaitAsync(TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), task1Started, null));
			});

			CompletableFuture<Void> child2Task = Futures.runAsync(() -> {
				return Async.awaitAsync(TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), task2Started, null));
			});

			task1Started.waitAsync().join();
			task2Started.waitAsync().join();

			return Async.awaitAsync(
				child1Task,
				() -> Async.awaitAsync(child2Task));
		});
	}

	@Test
	public void testMultipleSwitchToMainThreadShouldNotHang() {
		((DerivedJoinableFutureFactory)this.asyncPump).AssumeConcurrentUse = true;

		StrongBox<JoinableFuture<Void>> task1 = new StrongBox<>();
		StrongBox<JoinableFuture<Void>> task2 = new StrongBox<>();
		AsyncManualResetEvent taskStarted = new AsyncManualResetEvent();
		AsyncManualResetEvent testEnded = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWork1Queued = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWork2Queued = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWork1Finished = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWork2Finished = new AsyncManualResetEvent();

		CompletableFuture<Void> separatedTask = Futures.runAsync(() -> {
			task1.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), dependentWork1Queued, null),
					() -> {
						dependentWork1Finished.set();
						return Futures.completedNull();
					});
			}));

			task2.set(asyncPump.runAsync(() -> {
				JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
				collection.add(task1.get());
				collection.join();

				return Async.awaitAsync(
					TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), dependentWork2Queued, null),
					() -> {
						dependentWork2Finished.set();
						return Async.awaitAsync(testEnded);
					});
			}));

			taskStarted.set();
			return Async.awaitAsync(testEnded);
		});

		asyncPump.run(() -> {
			return Async.awaitAsync(
				taskStarted,
				() -> Async.awaitAsync(
					dependentWork1Queued,
					() -> Async.awaitAsync(
						dependentWork2Queued,
						() -> {
							JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
							collection.add(task2.get());
							collection.join();

							return Async.awaitAsync(
								dependentWork1Finished,
								() -> Async.awaitAsync(
									dependentWork2Finished,
									() -> {
										testEnded.set();
										return Futures.completedNull();
									}));
						})));
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> {
					return Async.awaitAsync(
						task1.get(),
						() -> Async.awaitAsync(
							task2.get(),
							() -> Async.awaitAsync(separatedTask)));
				});
		});
	}

	@Test
	public void testSwitchToMainThreadWithDelayedDependencyShouldNotHang() {
		StrongBox<JoinableFuture<Void>> task1 = new StrongBox<>();
		StrongBox<JoinableFuture<Void>> task2 = new StrongBox<>();
		AsyncManualResetEvent taskStarted = new AsyncManualResetEvent();
		AsyncManualResetEvent testEnded = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWorkAllowed = new AsyncManualResetEvent();
		AsyncManualResetEvent indirectDependencyAllowed = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWorkFinished = new AsyncManualResetEvent();

		CompletableFuture<Void> separatedTask = Futures.runAsync(() -> {
			JoinableFutureCollection taskCollection = new JoinableFutureCollection(context);
			JoinableFutureFactory factory = new JoinableFutureFactory(taskCollection);
			task1.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					dependentWorkAllowed,
					() -> Async.awaitAsync(
						TestUtilities.yieldAndNotify(factory.switchToMainThreadAsync().getAwaiter(), dependentWorkQueued, null),
						() -> {
							dependentWorkFinished.set();
							return Futures.completedNull();
						}));
			}));

			task2.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					indirectDependencyAllowed,
					() -> {
						JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
						collection.add(task1.get());

						return Async.awaitAsync(
							Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT),
							() -> {
								collection.join();
								return Async.awaitAsync(testEnded);
							});
					});
			}));

			taskStarted.set();
			return Async.awaitAsync(testEnded);
		});

		asyncPump.run(() -> {
			return Async.awaitAsync(
				taskStarted,
				() -> {
					dependentWorkAllowed.set();
					return Async.awaitAsync(
						dependentWorkQueued,
						() -> {
							JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
							collection.add(task2.get());

							collection.join();
							indirectDependencyAllowed.set();

							return Async.awaitAsync(
								dependentWorkFinished,
								() -> {
									testEnded.set();
									return Futures.completedNull();
								});
						});
				});
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> {
					return Async.awaitAsync(
						task1.get(),
						() -> Async.awaitAsync(
							task2.get(),
							() -> Async.awaitAsync(separatedTask)));
				});
		});
	}

	@Test
	public void testDoubleJoinedFutureDisjoinCorrectly() {
		StrongBox<JoinableFuture<Void>> task1 = new StrongBox<>();
		AsyncManualResetEvent taskStarted = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentFirstWorkCompleted = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentSecondWorkAllowed = new AsyncManualResetEvent();
		AsyncManualResetEvent mainThreadDependentSecondWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent testEnded = new AsyncManualResetEvent();

		CompletableFuture<Void> separatedTask = Futures.runAsync(() -> {
			task1.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> Async.awaitAsync(
						ForkJoinPool.commonPool(),
						() -> {
							dependentFirstWorkCompleted.set();
							return Async.awaitAsync(
								dependentSecondWorkAllowed,
								() -> Async.awaitAsync(TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), mainThreadDependentSecondWorkQueued, null)));
						}));
			}));

			taskStarted.set();
			return Async.awaitAsync(testEnded);
		});

		asyncPump.run(() -> {
			return Async.awaitAsync(
				taskStarted,
				() -> {
					JoinableFutureCollection collection1 = new JoinableFutureCollection(joinableCollection.getContext());
					collection1.add(task1.get());
					JoinableFutureCollection collection2 = new JoinableFutureCollection(joinableCollection.getContext());
					collection2.add(task1.get());

					return Async.awaitAsync(
						Async.usingAsync(
							collection1.join(),
							() -> {
								return Async.awaitAsync(
									Async.usingAsync(
										collection2.join(),
										() -> Futures.completedNull()),
									() -> Async.awaitAsync(dependentFirstWorkCompleted));
							}),
						() -> {
							dependentSecondWorkAllowed.set();
							return Async.awaitAsync(
								mainThreadDependentSecondWorkQueued,
								() -> Async.awaitAsync(
									Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT),
									() -> Async.awaitAsync(
										Async.yieldAsync(),
										() -> {
											Assert.assertFalse(task1.get().isDone());
											testEnded.set();
											return Futures.completedNull();
										})));
						});
				});
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> Async.awaitAsync(
					task1.get(),
					() -> Async.awaitAsync(separatedTask)));
		});
	}

	@Test
	public void testDoubleIndirectJoinedFutureDisjoinCorrectly() {
		StrongBox<JoinableFuture<Void>> task1 = new StrongBox<>();
		StrongBox<JoinableFuture<Void>> task2 = new StrongBox<>();
		StrongBox<JoinableFuture<Void>> task3 = new StrongBox<>();
		AsyncManualResetEvent taskStarted = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentFirstWorkCompleted = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentSecondWorkAllowed = new AsyncManualResetEvent();
		AsyncManualResetEvent mainThreadDependentSecondWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent testEnded = new AsyncManualResetEvent();

		CompletableFuture<Void> separatedTask = Futures.runAsync(() -> {
			task1.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> {
						return Async.awaitAsync(
							ForkJoinPool.commonPool(),
							() -> {
								dependentFirstWorkCompleted.set();
								return Async.awaitAsync(
									dependentSecondWorkAllowed,
									() -> Async.awaitAsync(
										TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), mainThreadDependentSecondWorkQueued, null)));
							});
					});
			}));

			task2.set(asyncPump.runAsync(() -> {
				JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
				collection.add(task1.get());
				return Async.usingAsync(
					collection.join(),
					() -> Async.awaitAsync(testEnded));
			}));

			task3.set(asyncPump.runAsync(() -> {
				JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
				collection.add(task1.get());
				return Async.usingAsync(
					collection.join(),
					() -> Async.awaitAsync(testEnded));
			}));

			taskStarted.set();
			return Async.awaitAsync(testEnded);
		});

		WaitCountingJoinableFutureFactory waitCountingJTF = new WaitCountingJoinableFutureFactory(asyncPump.getContext());
		waitCountingJTF.run(() -> {
			return Async.awaitAsync(
				taskStarted,
				() -> {
					JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
					collection.add(task2.get());
					collection.add(task3.get());

					return Async.awaitAsync(
						Async.usingAsync(
							collection.join(),
							() -> Async.awaitAsync(dependentFirstWorkCompleted)),
						() -> {
							int waitCountBeforeSecondWork = waitCountingJTF.getWaitCount();
							dependentSecondWorkAllowed.set();
							return Async.awaitAsync(
								Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT),
								() -> Async.awaitAsync(
									mainThreadDependentSecondWorkQueued,
									() -> Async.awaitAsync(
										Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT),
										() -> Async.awaitAsync(
											Async.yieldAsync(),
											() -> {
												// we expect 3 switching from two delay one yield call.  We don't want one triggered by Task1.
												Assert.assertTrue(waitCountingJTF.getWaitCount() - waitCountBeforeSecondWork <= 3);
												Assert.assertFalse(task1.get().isDone());

												testEnded.set();
												return Futures.completedNull();
											}))));
						});
				});
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> Async.awaitAsync(
					task1.get(),
					() -> Async.awaitAsync(
						task2.get(),
						() -> Async.awaitAsync(
							task3.get(),
							() -> Async.awaitAsync(separatedTask)))));
		});
	}

	/**
	 * Main -> Task1, Main -> Task2, Task1 &lt;-&gt; Task2 (loop dependency between Task1 and Task2.
	 */
	@Test
	public void testJoinWithLoopDependentFutures() {
		StrongBox<JoinableFuture<Void>> task1 = new StrongBox<>(null);
		StrongBox<JoinableFuture<Void>> task2 = new StrongBox<>(null);
		AsyncManualResetEvent taskStarted = new AsyncManualResetEvent();
		AsyncManualResetEvent testStarted = new AsyncManualResetEvent();
		AsyncManualResetEvent task1Prepared = new AsyncManualResetEvent();
		AsyncManualResetEvent task2Prepared = new AsyncManualResetEvent();
		AsyncManualResetEvent mainThreadDependentFirstWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentFirstWorkCompleted = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentSecondWorkAllowed = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentSecondWorkCompleted = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentThirdWorkAllowed = new AsyncManualResetEvent();
		AsyncManualResetEvent mainThreadDependentThirdWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent testEnded = new AsyncManualResetEvent();

		CompletableFuture<Void> separatedTask = Futures.runAsync(() -> {
			task1.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					taskStarted,
					() -> Async.awaitAsync(
						testStarted,
						() -> {
							JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
							collection.add(task2.get());
							return Async.usingAsync(
								collection.join(),
								() -> {
									task1Prepared.set();

									return Async.awaitAsync(
										TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), mainThreadDependentFirstWorkQueued, null),
										() -> Async.awaitAsync(
											ForkJoinPool.commonPool(),
											() -> {
												dependentFirstWorkCompleted.set();

												return Async.awaitAsync(
													dependentSecondWorkAllowed,
													() -> Async.awaitAsync(
														asyncPump.switchToMainThreadAsync(),
														() -> Async.awaitAsync(
															ForkJoinPool.commonPool(),
															() -> {
																dependentSecondWorkCompleted.set();

																return Async.awaitAsync(
																	dependentThirdWorkAllowed,
																	() -> Async.awaitAsync(
																		TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), mainThreadDependentThirdWorkQueued, null)));
															})));
											}));
								});
						}));
			}));

			task2.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					taskStarted,
					() -> Async.awaitAsync(
						testStarted,
						() -> {
							JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
							collection.add(task1.get());
							return Async.usingAsync(
								collection.join(),
								() -> {
									task2Prepared.set();
									return Async.awaitAsync(testEnded);
								});
						}));
			}));

			taskStarted.set();
			return Async.awaitAsync(testEnded);
		});

		WaitCountingJoinableFutureFactory waitCountingJTF = new WaitCountingJoinableFutureFactory(asyncPump.getContext());
		waitCountingJTF.run(() -> {
			return Async.awaitAsync(
				taskStarted,
				() -> {
					testStarted.set();
					return Async.awaitAsync(
						task1Prepared,
						() -> Async.awaitAsync(
							task2Prepared,
							() -> {
								JoinableFutureCollection collection1 = new JoinableFutureCollection(joinableCollection.getContext());
								collection1.add(task1.get());
								JoinableFutureCollection collection2 = new JoinableFutureCollection(joinableCollection.getContext());
								collection2.add(task2.get());
								return Async.awaitAsync(
									mainThreadDependentFirstWorkQueued,
									() -> {
										return Async.awaitAsync(
											Async.usingAsync(
												collection2.join(),
												() -> {
													return Async.awaitAsync(
														Async.usingAsync(
															collection1.join(),
															() -> Async.awaitAsync(dependentFirstWorkCompleted)),
														() -> {
															dependentSecondWorkAllowed.set();
															return Async.awaitAsync(dependentSecondWorkCompleted);
														});
												}),
											() -> {
												int waitCountBeforeSecondWork = waitCountingJTF.getWaitCount();
												dependentThirdWorkAllowed.set();

												return Async.awaitAsync(
													Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT),
													() -> Async.awaitAsync(
														mainThreadDependentThirdWorkQueued,
														() -> Async.awaitAsync(
															Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT),
															() -> Async.awaitAsync(
																Async.yieldAsync(),
																() -> {
																	// we expect 3 switching from two delay one yield call.  We don't want one triggered by Task1.
																	Assert.assertTrue(waitCountingJTF.getWaitCount() - waitCountBeforeSecondWork <= 3);
																	Assert.assertFalse(task1.get().isDone());

																	testEnded.set();
																	return Futures.completedNull();
																}))));
											});
									});
							}));
				});
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> {
					return Async.awaitAsync(
						task1.get(),
						() -> Async.awaitAsync(
							task2.get(),
							() -> Async.awaitAsync(
								separatedTask)));
				});
		});
	}

	@Test
	public void testDeepLoopedJoinedFutureDisjoinCorrectly() {
		StrongBox<JoinableFuture<Void>> task1 = new StrongBox<>(null);
		StrongBox<JoinableFuture<Void>> task2 = new StrongBox<>(null);
		StrongBox<JoinableFuture<Void>> task3 = new StrongBox<>(null);
		StrongBox<JoinableFuture<Void>> task4 = new StrongBox<>(null);
		StrongBox<JoinableFuture<Void>> task5 = new StrongBox<>(null);
		AsyncManualResetEvent taskStarted = new AsyncManualResetEvent();
		AsyncManualResetEvent task2Prepared = new AsyncManualResetEvent();
		AsyncManualResetEvent task3Prepared = new AsyncManualResetEvent();
		AsyncManualResetEvent task4Prepared = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentFirstWorkCompleted = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentSecondWorkAllowed = new AsyncManualResetEvent();
		AsyncManualResetEvent mainThreadDependentSecondWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent testEnded = new AsyncManualResetEvent();

		CompletableFuture<Void> separatedTask = Futures.runAsync(() -> {
			task1.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					taskStarted,
					() -> {
						JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
						collection.add(task1.get());
						return Async.usingAsync(
							collection.join(),
							() -> {
								return Async.awaitAsync(
									asyncPump.switchToMainThreadAsync(),
									() -> Async.awaitAsync(
										ForkJoinPool.commonPool(),
										() -> {
											dependentFirstWorkCompleted.set();
											return Async.awaitAsync(
												dependentSecondWorkAllowed,
												() -> Async.awaitAsync(
													TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), mainThreadDependentSecondWorkQueued, null)));
										}));
							});
					});
			}));

			task2.set(asyncPump.runAsync(() -> {
				JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
				collection.add(task1.get());
				return Async.usingAsync(
					collection.join(),
					() -> {
						task2Prepared.set();
						return Async.awaitAsync(testEnded);
					});
			}));

			task3.set(asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					taskStarted,
					() -> {
						JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
						collection.add(task2.get());
						collection.add(task4.get());
						return Async.usingAsync(
							collection.join(),
							() -> {
								task3Prepared.set();
								return Async.awaitAsync(testEnded);
							});
					});
			}));

			task4.set(asyncPump.runAsync(() -> {
				JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
				collection.add(task2.get());
				collection.add(task3.get());
				return Async.usingAsync(
					collection.join(),
					() -> {
						task4Prepared.set();
						return Async.awaitAsync(testEnded);
					});
			}));

			task5.set(asyncPump.runAsync(() -> {
				JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
				collection.add(task3.get());
				return Async.usingAsync(
					collection.join(),
					() -> {
						return Async.awaitAsync(testEnded);
					});
			}));

			taskStarted.set();
			return Async.awaitAsync(testEnded);
		});

		WaitCountingJoinableFutureFactory waitCountingJTF = new WaitCountingJoinableFutureFactory(asyncPump.getContext());
		waitCountingJTF.run(() -> {
			return Async.awaitAsync(
				taskStarted,
				() -> Async.awaitAsync(
					task2Prepared,
					() -> Async.awaitAsync(
						task3Prepared,
						() -> Async.awaitAsync(
							task4Prepared,
							() -> {
								JoinableFutureCollection collection = new JoinableFutureCollection(joinableCollection.getContext());
								collection.add(task5.get());

								return Async.awaitAsync(
									Async.usingAsync(
										collection.join(),
										() -> Async.awaitAsync(dependentFirstWorkCompleted)),
									() -> {
										int waitCountBeforeSecondWork = waitCountingJTF.getWaitCount();
										dependentSecondWorkAllowed.set();

										return Async.awaitAsync(
											Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT),
											() -> Async.awaitAsync(
												mainThreadDependentSecondWorkQueued,
												() -> Async.awaitAsync(
													Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT),
													() -> Async.awaitAsync(
														Async.yieldAsync(),
														() -> {
															// we expect 3 switching from two delay one yield call.  We don't want one triggered by Task1.
															Assert.assertTrue(waitCountingJTF.getWaitCount() - waitCountBeforeSecondWork <= 3);
															Assert.assertFalse(task1.get().isDone());

															testEnded.set();
															return Futures.completedNull();
														}))));
									});
							}))));
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> {
					return Async.awaitAsync(
						task1.get(),
						() -> Async.awaitAsync(
							task2.get(),
							() -> Async.awaitAsync(
								task3.get(),
								() -> Async.awaitAsync(
									task4.get(),
									() -> Async.awaitAsync(
										task5.get(),
										() -> Async.awaitAsync(
											separatedTask))))));
				});
		});
	}

	@Test
	public void testJoinRejectsSubsequentWork() {
		AtomicBoolean outerCompleted = new AtomicBoolean(false);

		AsyncManualResetEvent mainThreadDependentWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent dependentWorkCompleted = new AsyncManualResetEvent();
		AsyncManualResetEvent joinReverted = new AsyncManualResetEvent();
		AsyncManualResetEvent postJoinRevertedWorkQueued = new AsyncManualResetEvent();
		AsyncManualResetEvent postJoinRevertedWorkExecuting = new AsyncManualResetEvent();
		CompletableFuture<Void> unrelatedTask = Futures.runAsync(() -> {
			// STEP 2
			return Async.awaitAsync(
				TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), mainThreadDependentWorkQueued, null),
				() -> {
					// STEP 4
					Assert.assertSame(originalThread, Thread.currentThread());
					dependentWorkCompleted.set();
					return Async.awaitAsync(
						joinReverted.waitAsync(),
						() -> {
							// STEP 6
							Assert.assertNotSame(originalThread, Thread.currentThread());
							return Async.awaitAsync(
								TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), postJoinRevertedWorkQueued, postJoinRevertedWorkExecuting),
								() -> {
									// STEP 8
									Assert.assertSame(originalThread, Thread.currentThread());
									return Futures.completedNull();
								});
						},
						false);
				});
		});

		asyncPump.run(() -> {
			// STEP 1
			Assert.assertSame(originalThread, Thread.currentThread());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertSame(originalThread, Thread.currentThread());
					return Async.awaitAsync(
						mainThreadDependentWorkQueued.waitAsync(),
						() -> {
							// STEP 3
							return Async.awaitAsync(
								Async.usingAsync(
									joinableCollection.join(),
									() -> Async.awaitAsync(dependentWorkCompleted.waitAsync())),
								() -> {
									// STEP 5
									joinReverted.set();
									return Async.awaitAsync(
										Async.whenAny(unrelatedTask, postJoinRevertedWorkQueued.waitAsync()),
										releasingTask -> {
											if (releasingTask == unrelatedTask & unrelatedTask.isCompletedExceptionally()) {
												// rethrow an error that has already occurred.
												unrelatedTask.join();
											}

											// STEP 7
											CompletableFuture<Void> executingWaitTask = postJoinRevertedWorkExecuting.waitAsync();
											return Async.awaitAsync(
												Async.whenAny(executingWaitTask, Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT)),
												completed -> {
													Assert.assertNotSame("Main thread work from unrelated task should not have executed.", executingWaitTask, completed);

													return Async.awaitAsync(
														Async.yieldAsync(),
														() -> {
															Assert.assertSame(originalThread, Thread.currentThread());
															outerCompleted.set(true);
															return Futures.completedNull();
														});
												});
										});
								});
						});
				});
		});

		Assert.assertTrue("Outer Run did not complete.", outerCompleted.get());

		// Allow background task's last Main thread work to finish.
		Assert.assertFalse(unrelatedTask.isDone());
		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> Async.awaitAsync(unrelatedTask));
		});
	}

	@Test
	public void testSyncContextRestoredAfterRun() {
		SynchronizationContext syncContext = SynchronizationContext.getCurrent();
		Assert.assertNotNull("We need a non-null sync context for this test to be useful.", syncContext);

		asyncPump.run(() -> {
			return Async.awaitAsync(Async.yieldAsync());
		});

		Assert.assertSame(syncContext, SynchronizationContext.getCurrent());
	}

	@Test
	public void testBackgroundSynchronousTransitionsToUIThreadSynchronous() {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			asyncPump.run(() -> {
				Assert.assertNotSame(originalThread, Thread.currentThread());
				return Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> {
						// The scenario here is that some code calls out, then back in, via a synchronous interface
						asyncPump.run(() -> {
							return Async.awaitAsync(
								Async.yieldAsync(),
								() -> Async.awaitAsync(testReentrancyOfUnrelatedDependentWork()));
						});

						return Futures.completedNull();
					});
			});
		});

		// Avoid a deadlock while waiting for test to complete.
		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> Async.awaitAsync(task));
		});
	}

	@Test
	public void testSwitchToMainThreadAwaiterReappliesAsyncLocalSyncContextOnContinuation() {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			asyncPump.run(() -> {
				Assert.assertNotSame(originalThread, Thread.currentThread());

				// Switching to the main thread here will get us the SynchronizationContext we need,
				// and the awaiter's GetResult() should apply the AsyncLocal sync context as well
				// to avoid deadlocks later.
				return Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> Async.awaitAsync(
						testReentrancyOfUnrelatedDependentWork(),
						() -> {
							// The scenario here is that some code calls out, then back in, via a synchronous interface
							asyncPump.run(() -> {
								return Async.awaitAsync(
									Async.yieldAsync(),
									() -> Async.awaitAsync(testReentrancyOfUnrelatedDependentWork()));
							});
							return Futures.completedNull();
						}));
			});
		});

		// Avoid a deadlock while waiting for test to complete.
		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> Async.awaitAsync(task));
		});
	}

	@Test
	@Ignore("Fails for Java only")
	public void testNestedJoinsDistinctAsyncPumps() {
		final int nestLevels = 3;
		StrongBox<MockAsyncService> outerService = new StrongBox<>();
		for (int level = 0; level < nestLevels; level++) {
			outerService.set(new MockAsyncService(asyncPump.getContext(), outerService.get()));
		}

		CompletableFuture<Void> operationTask = outerService.get().operationAsync();

		asyncPump.run(() -> {
			return Async.awaitAsync(outerService.get().stopAsync(operationTask));
		});

		Assert.assertTrue(operationTask.isDone());
	}

	@Test
	public void testSynchronousFutureStackMaintainedCorrectly() {
		asyncPump.run(() -> {
			asyncPump.run(() -> CompletableFuture.completedFuture(true));
			return Async.awaitAsync(Async.yieldAsync());
		});
	}

	@Test
	public void testSynchronousFutureStackMaintainedCorrectlyWithForkedTask() {
		AsyncManualResetEvent innerTaskWaitingSwitching = new AsyncManualResetEvent();

		asyncPump.run(() -> {
			StrongBox<CompletableFuture<Void>> innerTask = new StrongBox<>();
			asyncPump.run(() -> {
				// We need simulate a scenario that the task is completed without any yielding,
				// but the queue of the Joinable task is not empty at that point,
				// so the synchronous JoinableTask doesn't need any blocking time, but it is completed later.
				innerTask.set(Futures.runAsync(() -> {
					return Async.awaitAsync(
						TestUtilities.yieldAndNotify(
							asyncPump.switchToMainThreadAsync().getAwaiter(),
							innerTaskWaitingSwitching,
							null));
				}));

				innerTaskWaitingSwitching.waitAsync().join();
				return Futures.completedNull();
			});

			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					// Now, get rid of the innerTask
					return Async.awaitAsync(innerTask.get());
				});
		});
	}

	@Test
	@Ignore("ignored")
	public void testSynchronousFutureStackMaintainedCorrectlyWithForkedFuture2() {
		AsyncManualResetEvent innerTaskWaiting = new AsyncManualResetEvent();

		// This test simulates that we have an inner task starts to switch to main thread after the joinable task is compeleted.
		// Because completed task won't be tracked in the dependent chain, waiting it causes a deadlock.  This could be a potential problem.
		asyncPump.run(() -> {
			StrongBox<CompletableFuture<Void>> innerTask = new StrongBox<>();
			asyncPump.run(() -> {
				innerTask.set(Futures.runAsync(() -> {
					return Async.awaitAsync(
						innerTaskWaiting.waitAsync(),
						() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync()));
				}));

				return Futures.completedNull();
			});

			innerTaskWaiting.set();
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					// Now, get rid of the innerTask
					return Async.awaitAsync(innerTask.get());
				});
		});
	}

	@Test
	public void testRunSynchronouslyKicksOffReturnsThenSyncBlocksStillRequiresJoin() {
		AsyncManualResetEvent mainThreadNowBlocking = new AsyncManualResetEvent();
		StrongBox<CompletableFuture<Void>> task = new StrongBox<>();
		asyncPump.run(() -> {
			task.set(Futures.runAsync(() -> {
				return Async.awaitAsync(
					mainThreadNowBlocking.waitAsync(),
					() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync()));
			}));

			return Futures.completedNull();
		});

		asyncPump.run(() -> {
			mainThreadNowBlocking.set();
			return Async.awaitAsync(
				Async.whenAny(task.get(), Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT)),
				completed -> {
					Assert.assertNotSame(task, completed);
					return Async.usingAsync(
						joinableCollection.join(),
						() -> Async.awaitAsync(task.get()));
				});
		});
	}

	@Test
	public void testKickOffAsyncWorkFromMainThreadThenBlockOnIt() {
		JoinableFuture<Void> joinable = asyncPump.runAsync(() -> {
			return Async.awaitAsync(someOperationThatMayBeOnMainThreadAsync());
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> Async.awaitAsync(joinable.getFuture()));
		});
	}

	@Test
	public void testKickOffDeepAsyncWorkFromMainThreadThenBlockOnIt() {
		JoinableFuture<Void> joinable = asyncPump.runAsync(() -> {
			return Async.awaitAsync(someOperationThatUsesMainThreadViaItsOwnAsyncPumpAsync());
		});

		asyncPump.run(() -> {
			return Async.usingAsync(
				joinableCollection.join(),
				() -> Async.awaitAsync(joinable.getFuture()));
		});
	}

	@Test
	public void testBeginAsyncCompleteSync() {
		CompletableFuture<? extends Void> task = asyncPump.runAsync(
			() -> someOperationThatUsesMainThreadViaItsOwnAsyncPumpAsync()).getFuture();
		Assert.assertFalse(task.isDone());
		TestUtilities.completeSynchronously(asyncPump, joinableCollection, task);
	}

	@Test
	public void testBeginAsyncYieldsWhenDelegateYieldsOnUIThread() {
		AtomicBoolean afterYieldReached = new AtomicBoolean(false);
		CompletableFuture<? extends Void> task = asyncPump.runAsync(() -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					afterYieldReached.set(true);
					return Futures.<Void>completedNull();
				});
		}).getFuture();

		Assert.assertFalse(afterYieldReached.get());
		TestUtilities.completeSynchronously(asyncPump, joinableCollection, task);
		Assert.assertTrue(afterYieldReached.get());
	}

	@Test
	public void testBeginAsyncYieldsWhenDelegateYieldsOffUIThread() {
		AtomicBoolean afterYieldReached = new AtomicBoolean(false);
		AsyncManualResetEvent backgroundThreadWorkDoneEvent = new AsyncManualResetEvent();
		CompletableFuture<? extends Void> task = asyncPump.runAsync(() -> {
			return Async.awaitAsync(
				backgroundThreadWorkDoneEvent,
				() -> {
					afterYieldReached.set(true);
					return Futures.<Void>completedNull();
				});
		}).getFuture();

		Assert.assertFalse(afterYieldReached.get());
		backgroundThreadWorkDoneEvent.set();
		TestUtilities.completeSynchronously(asyncPump, joinableCollection, task);
		Assert.assertTrue(afterYieldReached.get());
	}

	@Test
	public void testBeginAsyncYieldsToAppropriateContext() throws Exception {
		CompletableFuture<? extends Void> backgroundWork = Futures.supply(() -> {
			return asyncPump.runAsync(() -> {
				// Verify that we're on a background thread and stay there.
				Assert.assertNotSame(originalThread, Thread.currentThread());
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> {
						Assert.assertNotSame(originalThread, Thread.currentThread());

						// Now explicitly get on the Main thread, and verify that we stay there.
						return Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(),
							() -> {
								Assert.assertSame(originalThread, Thread.currentThread());
								return Async.awaitAsync(
									Async.yieldAsync(),
									() -> {
										Assert.assertSame(originalThread, Thread.currentThread());
										return Futures.<Void>completedNull();
									});
							});
					});
			}).getFuture();
		}).get();

		TestUtilities.completeSynchronously(asyncPump, joinableCollection, backgroundWork);
	}

	@Test
	public void testRunSynchronouslyYieldsToAppropriateContext() {
		for (int i = 0; i < 100; i++) {
			CompletableFuture<Void> backgroundWork = Futures.runAsync(() -> {
				asyncPump.run(() -> {
					// Verify that we're on a background thread and stay there.
					Assert.assertNotSame(originalThread, Thread.currentThread());
					return Async.awaitAsync(
						Async.yieldAsync(),
						() -> {
							Assert.assertNotSame(originalThread, Thread.currentThread());

							// Now explicitly get on the Main thread, and verify that we stay there.
							return Async.awaitAsync(
								asyncPump.switchToMainThreadAsync(),
								() -> {
									Assert.assertSame(originalThread, Thread.currentThread());
									return Async.awaitAsync(
										Async.yieldAsync(),
										() -> {
											Assert.assertSame(originalThread, Thread.currentThread());
											return Futures.completedNull();
										});
								});
						});
				});
			});

			TestUtilities.completeSynchronously(asyncPump, joinableCollection, backgroundWork);
		}
	}

	@Test
	public void testBeginAsyncOnMTAKicksOffOtherAsyncPumpWorkCanCompleteSynchronouslySwitchFirst() {
		JoinableFutureCollection otherCollection = asyncPump.getContext().createCollection();
		JoinableFutureFactory otherPump = asyncPump.getContext().createFactory(otherCollection);
		AtomicBoolean taskFinished = new AtomicBoolean(false);
		CompletableFuture<Void> switchPended = new CompletableFuture<>();

		// Kick off the BeginAsync work from a background thread that has no special
		// affinity to the main thread.
		JoinableFuture<Void> joinable = Futures.supply(() -> {
			return asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> {
						Awaiter<Void> awaiter = otherPump.switchToMainThreadAsync().getAwaiter();
						Assert.assertFalse(awaiter.isDone());
						AsyncManualResetEvent continuationFinished = new AsyncManualResetEvent();
						awaiter.onCompleted(() -> {
							taskFinished.set(true);
							continuationFinished.set();
						});
						switchPended.complete(null);
						return Async.awaitAsync(continuationFinished);
					});
			});
		}).join();

		Assert.assertFalse(joinable.getFuture().isDone());
		switchPended.join();
		joinable.join();
		Assert.assertTrue(taskFinished.get());
		Assert.assertTrue(joinable.getFuture().isDone());
	}

	@Test
	public void testBeginAsyncOnMTAKicksOffOtherAsyncPumpWorkCanCompleteSynchronouslyJoinFirst() {
		JoinableFutureCollection otherCollection = asyncPump.getContext().createCollection();
		JoinableFutureFactory otherPump = asyncPump.getContext().createFactory(otherCollection);
		AtomicBoolean taskFinished = new AtomicBoolean(false);
		AsyncManualResetEvent joinedEvent = new AsyncManualResetEvent();

		// Kick off the BeginAsync work from a background thread that has no special
		// affinity to the main thread.
		JoinableFuture<Void> joinable = Futures.supply(() -> {
			return asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					joinedEvent,
					() -> Async.awaitAsync(
						otherPump.switchToMainThreadAsync(),
						() -> {
							taskFinished.set(true);
							return Futures.<Void>completedNull();
						}));
			});
		}).join();

		Assert.assertFalse(joinable.getFuture().isDone());
		asyncPump.run(() -> {
			CompletableFuture<Void> awaitable = joinable.joinAsync();
			joinedEvent.set();
			return Async.awaitAsync(awaitable);
		});
		Assert.assertTrue(taskFinished.get());
		Assert.assertTrue(joinable.getFuture().isDone());
	}

	@Test
	public void testBeginAsyncWithResultOnMTAKicksOffOtherAsyncPumpWorkCanCompleteSynchronously() {
		JoinableFutureCollection otherCollection = asyncPump.getContext().createCollection();
		JoinableFutureFactory otherPump = asyncPump.getContext().createFactory(otherCollection);
		AtomicBoolean taskFinished = new AtomicBoolean(false);

		// Kick off the BeginAsync work from a background thread that has no special
		// affinity to the main thread.
		JoinableFuture<Integer> joinable = Futures.supply(() -> {
			return asyncPump.runAsync(() -> {
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> Async.awaitAsync(
						otherPump.switchToMainThreadAsync(),
						() -> {
							taskFinished.set(true);
							return CompletableFuture.completedFuture(5);
						}));
			});
		}).join();

		Assert.assertFalse(joinable.getFuture().isDone());
		int result = joinable.join();
		Assert.assertEquals(5, result);
		Assert.assertTrue(taskFinished.get());
		Assert.assertTrue(joinable.getFuture().isDone());
	}

	@Test
	public void testJoinCancellation() {
		// Kick off the BeginAsync work from a background thread that has no special
		// affinity to the main thread.
		JoinableFuture<Void> joinable = asyncPump.runAsync(() -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> Async.awaitAsync(Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT))));
		});

		Assert.assertFalse(joinable.getFuture().isDone());
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource(Duration.ofMillis(ASYNC_DELAY_UNIT.toMillis(ASYNC_DELAY / 4)));

		// This isn't the cancellation behavior we want...
		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(CancellationException.class));
		joinable.join(cancellationTokenSource.getToken());
	}

	@Test
	public void testRunSynchronouslyFutureOfTWithFireAndForgetMethod() {
		asyncPump.run(() -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					someFireAndForgetMethod();
					return Async.awaitAsync(
						Async.yieldAsync(),
						() -> Async.awaitAsync(Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT)));
				});
		});
	}

	@Test
	public void testSendToSyncContextCapturedFromWithinRunSynchronously() {
		AsyncCountdownEvent countdownEvent = new AsyncCountdownEvent(2);
		GenericParameterHelper state = new GenericParameterHelper(3);
		StrongBox<SynchronizationContext> syncContext = new StrongBox<>(null);
		StrongBox<CompletableFuture<Void>> sendFromWithinRunSync = new StrongBox<>(null);
		asyncPump.run(() -> {
			syncContext.set(SynchronizationContext.getCurrent());

			AtomicBoolean executed1 = new AtomicBoolean(false);
			syncContext.get().send(
				s -> {
					Assert.assertSame(originalThread, Thread.currentThread());
					Assert.assertSame(state, s);
					executed1.set(true);
				},
				state);
			Assert.assertTrue(executed1.get());

			// And from another thread.  But the Main thread is "busy" in a synchronous block,
			// so the Send isn't expected to get in right away.  So spin off a task to keep the Send
			// in a wait state until it's finally able to get through.
			// This tests that Send can work even if not immediately.
			sendFromWithinRunSync.set(Futures.runAsync(() -> {
				AtomicBoolean executed2 = new AtomicBoolean(false);
				syncContext.get().send(
					s -> {
					try {
						Assert.assertSame(this.originalThread, Thread.currentThread());
						Assert.assertSame(state, s);
						executed2.set(true);
					} finally {
						// Allow the message pump to exit.
						countdownEvent.signal();
					}
				},
					state);
				Assert.assertTrue(executed2.get());
			}));

			return Futures.completedNull();
		});

		// From the Main thread.
		AtomicBoolean executed3 = new AtomicBoolean(false);
		syncContext.get().send(
			s -> {
				Assert.assertSame(originalThread, Thread.currentThread());
				Assert.assertSame(state, s);
				executed3.set(true);
			},
			state);
		Assert.assertTrue(executed3.get());

		// And from another thread.
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			try {
				AtomicBoolean executed4 = new AtomicBoolean(false);
				syncContext.get().send(
					s -> {
						Assert.assertSame(originalThread, Thread.currentThread());
						Assert.assertSame(state, s);
						executed4.set(true);
					},
					state);
				Assert.assertTrue(executed4.get());
			} finally {
				// Allow the message pump to exit.
				countdownEvent.signal();
			}
		});

		countdownEvent.waitAsync().thenRun(() -> testFrame.setContinue(false));

		pushFrame();

		// throw exceptions for any failures.
		task.join();
		sendFromWithinRunSync.get().join();
	}

	@Test
	public void testSendToSyncContextCapturedAfterSwitchingToMainThread() {
		GenericParameterHelper state = new GenericParameterHelper(3);
		StrongBox<SynchronizationContext> syncContext = new StrongBox<>(null);
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			return Async.finallyAsync(
				// starting on a worker thread, we switch to the Main thread.
				Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> {
						syncContext.set(SynchronizationContext.getCurrent());

						AtomicBoolean executed1 = new AtomicBoolean(false);
						syncContext.get().send(
							s -> {
								Assert.assertSame(originalThread, Thread.currentThread());
								Assert.assertSame(state, s);
								executed1.set(true);
							},
							state);
						Assert.assertTrue(executed1.get());

						return Async.awaitAsync(
							ForkJoinPool.commonPool(),
							() -> {
								AtomicBoolean executed2 = new AtomicBoolean(false);
								syncContext.get().send(
									s -> {
										Assert.assertSame(originalThread, Thread.currentThread());
										Assert.assertSame(state, s);
										executed2.set(true);
									},
									state);
								Assert.assertTrue(executed2.get());
								return Futures.completedNull();
							});
					}),
				() -> {
					// Allow the pushed message pump frame to exit.
					testFrame.setContinue(false);
				});
		});

		// Open message pump so the background thread can switch to the Main thread.
		pushFrame();

		// observe any exceptions thrown.
		task.join();
	}

	/**
	 * This test verifies that in the event that a Run method executes a delegate that invokes modal UI, where the WPF
	 * dispatcher would normally process Posted messages, that our applied SynchronizationContext will facilitate the
	 * same expedited message delivery.
	 */
	@Test
	public void testPostedMessagesAlsoSentToDispatcher() {
		asyncPump.run(() -> {
			// simulate someone who has captured our own sync context.
			SynchronizationContext syncContext = SynchronizationContext.getCurrent();
			StrongBox<Throwable> ex = new StrongBox<>(null);
			return Async.awaitAsync(
				Async.usingAsync(
					context.suppressRelevance(),
					() -> {
						// simulate some kind of sync context hand-off that doesn't flow execution context.
						Futures.runAsync(() -> {
							// This post will only get a chance for processing
							syncContext.post(
								state
								-> {
								try {
									Assert.assertSame(originalThread, Thread.currentThread());
								} catch (Throwable e) {
									ex.set(e);
								} finally {
									testFrame.setContinue(false);
								}
							},
								null);
						});

						return Futures.completedNull();
					}),
				() -> {
					// Now simulate the display of modal UI by pushing an unfiltered message pump onto the stack.
					// This will hang unless the message gets processed.
					pushFrame();

					if (ex.get() != null) {
						Assert.fail("Posted message threw an exception: " + ex);
					}

					return Futures.completedNull();
				});
		});
	}

	@Test
	public void testStackOverflowAvoidance() {
		StrongBox<CompletableFuture<Void>> backgroundTask = new StrongBox<>(null);
		AsyncManualResetEvent mainThreadUnblocked = new AsyncManualResetEvent();
		JoinableFutureCollection otherCollection = this.context.createCollection();
		JoinableFutureFactory otherPump = this.context.createFactory(otherCollection);
		otherPump.run(() -> {
			asyncPump.run(() -> {
				backgroundTask.set(Futures.runAsync(() -> {
					return Async.usingAsync(
						joinableCollection.join(),
						() -> {
							return Async.awaitAsync(
								mainThreadUnblocked,
								() -> Async.awaitAsync(
									asyncPump.switchToMainThreadAsync(),
									() -> {
										testFrame.setContinue(false);
										return Futures.completedNull();
									}));
						});
				}));

				return Futures.completedNull();
			});

			return Futures.completedNull();
		});

		mainThreadUnblocked.set();

		// The rest of this isn't strictly necessary for the hang, but it gets the test
		// to wait till the background task has either succeeded, or failed.
		pushFrame();
	}

	@Test
	public void testMainThreadExecutorDoesNotInlineWhileQueuingFutures() {
		CompletableFuture<Void> uiBoundWork = Futures.runAsync(() -> {
			return Async.awaitAsync(
				asyncPump.switchToMainThreadAsync(),
				() -> {
					testFrame.setContinue(false);
					return Futures.completedNull();
				});
		});

		Assert.assertTrue("The UI bound work should not have executed yet.", testFrame.getContinue());
		pushFrame();
	}

	@Test
	public void testJoinControllingSelf() {
		AsyncManualResetEvent runSynchronouslyExited = new AsyncManualResetEvent();
		CompletableFuture<Void> unblockMainThread = new CompletableFuture<>();
		StrongBox<CompletableFuture<Void>> backgroundTask = new StrongBox<>();
		CompletableFuture<Void> uiBoundWork;
		asyncPump.run(() -> {
			backgroundTask.set(Futures.runAsync(() -> {
				return Async.awaitAsync(
					runSynchronouslyExited,
					() -> {
						try {
							try (Disposable releaser = joinableCollection.join()) {
								unblockMainThread.complete(null);
							}
						} catch (Throwable ex) {
							unblockMainThread.complete(null);
							throw ex;
						}

						return Futures.completedNull();
					});
			}));

			return Futures.completedNull();
		});

		uiBoundWork = Futures.runAsync(() -> {
			return Async.awaitAsync(
				asyncPump.switchToMainThreadAsync(),
				() -> {
					testFrame.setContinue(false);
					return Futures.completedNull();
				});
		});

		runSynchronouslyExited.set();
		unblockMainThread.join();
		pushFrame();
		// rethrow any exceptions
		backgroundTask.get().join();
	}

	@Test
	public void testJoinWorkStealingRetainsThreadAffinityUI() {
		AtomicBoolean synchronousCompletionStarting = new AtomicBoolean(false);
		CompletableFuture<? extends Void> asyncTask = asyncPump.runAsync(() -> {
			AtomicInteger iterationsRemaining = new AtomicInteger(20);
			return Async.whileAsync(
				() -> iterationsRemaining.get() > 0,
				() -> {
					return Async.awaitAsync(
						Async.yieldAsync(),
						() -> {
							Assert.assertSame(originalThread, Thread.currentThread());

							if (synchronousCompletionStarting.get()) {
								iterationsRemaining.decrementAndGet();
							}

							return Futures.completedNull();
						});
				});
		}).getFuture();

		Futures.runAsync(() -> {
			synchronousCompletionStarting.set(true);
			TestUtilities.completeSynchronously(asyncPump, joinableCollection, asyncTask);
			Assert.assertTrue(asyncTask.isDone());
			testFrame.setContinue(false);
		});

		pushFrame();
		// realize any exceptions
		asyncTask.join();
	}

	@Test
	public void testJoinWorkStealingRetainsThreadAffinityBackground() {
		AtomicBoolean synchronousCompletionStarting = new AtomicBoolean(false);
		CompletableFuture<JoinableFuture<Void>> asyncTask = Futures.supply(() -> {
			return asyncPump.runAsync(() -> {
				AtomicInteger iterationsRemaining = new AtomicInteger(20);
				return Async.awaitAsync(
					Async.whileAsync(
						() -> iterationsRemaining.get() > 0,
						() -> {
							return Async.awaitAsync(
								Async.yieldAsync(),
								() -> {
									Assert.assertNotSame(originalThread, Thread.currentThread());

									if (synchronousCompletionStarting.get()) {
										iterationsRemaining.decrementAndGet();
									}

									return Futures.completedNull();
								});
						}),
					() -> {
						return Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(),
							() -> {
								return Async.forAsync(
									() -> 0,
									i -> i < 20,
									i -> i + 1,
									() -> {
										Assert.assertSame(originalThread, Thread.currentThread());
										return Async.awaitAsync(Async.yieldAsync());
									});
							});
					});
			});
		});

		synchronousCompletionStarting.set(true);
		TestUtilities.completeSynchronously(asyncPump, joinableCollection, asyncTask);
		Assert.assertTrue(asyncTask.isDone());
		// realize any exceptions
		asyncTask.join();
	}

	/**
	 * Verifies that yields in a BeginAsynchronously delegate still retain their ability to execute continuations
	 * on-demand when executed within a Join.
	 */
	@Test
	public void testBeginAsyncThenJoinOnMainThread() {
		JoinableFuture<Void> joinable = asyncPump.runAsync(() -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> Async.awaitAsync(Async.yieldAsync()));
		});

		// this Join will "host" the first and second continuations.
		joinable.join();
	}

	/**
	 * Verifies that yields in a BeginAsynchronously delegate still retain their ability to execute continuations
	 * on-demand from a Join call later on the main thread.
	 *
	 * <p>
	 * This test allows the first continuation to naturally execute as if it were asynchronous. Then it intercepts the
	 * main thread and Joins the original task, that has one continuation scheduled and another not yet scheduled. This
	 * test verifies that continuations retain an appropriate SynchronizationContext that will avoid deadlocks when
	 * async operations are synchronously blocked on.</p>
	 */
	@Test
	public void testBeginAsyncThenJoinOnMainThreadLater() {
		AsyncManualResetEvent firstYield = new AsyncManualResetEvent();
		AsyncManualResetEvent startingJoin = new AsyncManualResetEvent();
		((DerivedJoinableFutureFactory)this.asyncPump).AssumeConcurrentUse = true;

		JoinableFuture<Void> joinable = asyncPump.runAsync(() -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					firstYield.set();
					return Async.awaitAsync(
						startingJoin,
						() -> {
							testFrame.setContinue(false);
							return Futures.completedNull();
						});
				});
		});

		CompletableFuture<Void> forcingFactor = Futures.runAsync(() -> {
			return Async.awaitAsync(
				asyncPump.switchToMainThreadAsync(),
				() -> Async.awaitAsync(
					Async.yieldAsync(),
					() -> {
						startingJoin.set();
						joinable.join();
						return Futures.completedNull();
					}));
		});

		pushFrame();
	}

	@Test
	public void testRunSynchronouslyWithoutSyncContext() {
		SynchronizationContext.setSynchronizationContext(null);
		this.context = new JoinableFutureContext();
		this.joinableCollection = context.createCollection();
		this.asyncPump = context.createFactory(joinableCollection);
		this.asyncPump.run(() -> Async.awaitAsync(Async.yieldAsync()));
	}

	/**
	 * Verifies the fix for a bug found in actual Visual Studio use of the AsyncPump.
	 */
	@Test
	public void testAsyncPumpEnumeratingModifiedCollection() {
		// Arrange for a pending action on this.asyncPump.
		AsyncManualResetEvent messagePosted = new AsyncManualResetEvent();
		CompletableFuture<Void> uiThreadReachedTask = Futures.runAsync(() -> {
			return Async.awaitAsync(
				TestUtilities.yieldAndNotify(asyncPump.switchToMainThreadAsync().getAwaiter(), messagePosted, null));
		});

		// The repro in VS wasn't as concise (or possibly as contrived looking) as this.
		// This code sets up the minimal scenario for reproducing the bug that came about
		// through interactions of various CPS/VC components.
		JoinableFutureCollection otherCollection = context.createCollection();
		JoinableFutureFactory otherPump = context.createFactory(otherCollection);
		otherPump.run(() -> {
			return Async.awaitAsync(asyncPump.runAsync(() -> {
				return Futures.runAsync(() -> {
					// wait for this.asyncPump.pendingActions to be non empty
					return Async.awaitAsync(
						messagePosted,
						() -> {
							return Async.usingAsync(
								joinableCollection.join(),
								() -> Async.awaitAsync(uiThreadReachedTask));
						});
				});
			}));
		});
	}

	@Test
	public void testNoPostedMessageLost() throws Exception {
		Futures.runAsync(() -> {
			AsyncManualResetEvent delegateExecuted = new AsyncManualResetEvent();
			StrongBox<SynchronizationContext> syncContext = new StrongBox<>();
			asyncPump.run(() -> {
				syncContext.set(SynchronizationContext.getCurrent());
				return Futures.completedNull();
			});
			syncContext.get().post(
				state -> delegateExecuted.set(),
				null);
			return Async.awaitAsync(delegateExecuted);
		}).get(TEST_TIMEOUT, TEST_TIMEOUT_UNIT);
	}

	@Test
	public void testNestedSyncContextsAvoidDeadlocks() {
		asyncPump.run(() -> {
			return Async.awaitAsync(asyncPump.runAsync(
				() -> Async.awaitAsync(Async.yieldAsync())));
		});
	}

	/**
	 * Verifies when {@link JoinableFuture}s are nested that all factories' policies are involved in trying to get to
	 * the UI thread.
	 */
	@Test
	public void testNestedFactoriesCombinedMainThreadPolicies() {
		ModalPumpingJoinableFutureFactory loPriFactory = new ModalPumpingJoinableFutureFactory(context);
		ModalPumpingJoinableFutureFactory hiPriFactory = new ModalPumpingJoinableFutureFactory(context);

		JoinableFuture<Void> outer = hiPriFactory.runAsync(() -> {
			return Async.awaitAsync(loPriFactory.runAsync(() -> {
				return Async.awaitAsync(Async.yieldAsync());
			}));
		});

		// Verify that the loPriFactory received the message.
		Assert.assertEquals(1, Iterables.size(loPriFactory.getJoinableFuturesPendingMainthread()));

		// Simulate a modal dialog, with a message pump that is willing
		// to execute hiPriFactory messages but not loPriFactory messages.
		hiPriFactory.doModalLoopUntilEmpty();
		Assert.assertTrue(outer.isDone());
	}

	/**
	 * Verifies when JoinableTasks are nested that nesting (parent) factories do not assist in reaching the main thread
	 * if the nesting JoinableTask completed before the child JoinableTask even started.
	 *
	 * <p>
	 * This is for efficiency as well as an accuracy assistance since the nested JTF may have a lower priority to get to
	 * the main thread (e.g. idle priority) than the parent JTF. If the parent JTF assists just because it happened to
	 * be active for a brief time when the child JoinableTask was created, it could forever defeat the intended lower
	 * priority of the child.</p>
	 */
	@Test
	public void testNestedFactoriesDoNotAssistChildrenOfFutureThatCompletedBeforeStart() {
		ModalPumpingJoinableFutureFactory loPriFactory = new ModalPumpingJoinableFutureFactory(context);
		ModalPumpingJoinableFutureFactory hiPriFactory = new ModalPumpingJoinableFutureFactory(context);

		AsyncManualResetEvent outerFinished = new AsyncManualResetEvent(false, /*allowInliningAwaiters:*/ true);
		StrongBox<JoinableFuture<Void>> innerTask = new StrongBox<>();
		AsyncManualResetEvent loPriSwitchPosted = new AsyncManualResetEvent();
		JoinableFuture<Void> outer = hiPriFactory.runAsync(() -> {
			Futures.runAsync(() -> {
				return Async.awaitAsync(
					outerFinished,
					() -> {
						innerTask.set(loPriFactory.runAsync(() -> {
							return Async.awaitAsync(
								TestUtilities.yieldAndNotify(loPriFactory.switchToMainThreadAsync().getAwaiter(), loPriSwitchPosted, null));
						}));
						return Futures.completedNull();
					});
			});

			return Futures.completedNull();
		});
		outerFinished.set();
		loPriSwitchPosted.waitAsync().join();

		// Verify that the loPriFactory received the message and hiPriFactory did not.
		Assert.assertEquals(1, Iterables.size(loPriFactory.getJoinableFuturesPendingMainthread()));
		Assert.assertEquals(0, Iterables.size(hiPriFactory.getJoinableFuturesPendingMainthread()));
	}

	/**
	 * Verifies when {@link JoinableFuture}s are nested that nesting (parent) factories do not assist in reaching the
	 * main thread once the nesting {@link JoinableFuture} completes (assuming it completes after the child
	 * {@link JoinableFuture} starts).
	 *
	 * <p>
	 * This is for efficiency as well as an accuracy assistance since the nested JTF may have a lower priority to get to
	 * the main thread (e.g. idle priority) than the parent JTF. If the parent JTF assists just because it happened to
	 * be active for a brief time when the child {@link JoinableFuture} was created, it could forever defeat the
	 * intended lower priority of the child.</p>
	 *
	 * <p>
	 * This test is ignored because fixing it would require a {@link JoinableFuture} to have a reference to its
	 * antecedent, or the antecedent to maintain a collection of child tasks. The first possibility is unpalatable
	 * (because it would create a memory leak for those who chain tasks together). The second one we sort of already do
	 * through the {@link JoinableFuture#childOrJoinedJobs} field, and we may wire it up through there in the
	 * future.</p>
	 */
	@Test
	@Ignore("Ignored")
	public void testNestedFactoriesDoNotAssistChildrenOfFutureThatCompletedAfterStart() {
		ModalPumpingJoinableFutureFactory loPriFactory = new ModalPumpingJoinableFutureFactory(context);
		ModalPumpingJoinableFutureFactory hiPriFactory = new ModalPumpingJoinableFutureFactory(context);

		AsyncManualResetEvent outerFinished = new AsyncManualResetEvent(false, /*allowInliningAwaiters:*/ true);
		StrongBox<JoinableFuture<Void>> innerTask = new StrongBox<>();
		JoinableFuture<Void> outer = hiPriFactory.runAsync(() -> {
			innerTask.set(loPriFactory.runAsync(() -> {
				return Async.awaitAsync(outerFinished);
			}));
			return Futures.completedNull();
		});
		outerFinished.set();

		// Verify that the loPriFactory received the message and hiPriFactory did not.
		Assert.assertEquals(1, Iterables.size(loPriFactory.getJoinableFuturesPendingMainthread()));
		Assert.assertEquals(0, Iterables.size(hiPriFactory.getJoinableFuturesPendingMainthread()));
	}

	/**
	 * Verifes that each instance of JTF is only notified once of a nested {@link JoinableFuture}'s attempt to get to
	 * the UI thread.
	 */
	@Test
	public void testNestedFactoriesDoNotDuplicateEffort() {
		ModalPumpingJoinableFutureFactory loPriFactory = new ModalPumpingJoinableFutureFactory(context);
		ModalPumpingJoinableFutureFactory hiPriFactory = new ModalPumpingJoinableFutureFactory(context);

		// For this test, we intentionally use each factory twice in a row.
		// We mix up the order in another test.
		JoinableFuture<Void> outer = hiPriFactory.runAsync(() -> {
			return Async.awaitAsync(
				hiPriFactory.runAsync(() -> {
					return Async.awaitAsync(
						loPriFactory.runAsync(() -> {
							return Async.awaitAsync(
								loPriFactory.runAsync(() -> {
									return Async.awaitAsync(Async.yieldAsync());
								}));
						}));
				}));
		});

		// Verify that each factory received the message exactly once.
		Assert.assertEquals(1, Iterables.size(loPriFactory.getJoinableFuturesPendingMainthread()));
		Assert.assertEquals(1, Iterables.size(hiPriFactory.getJoinableFuturesPendingMainthread()));

		// Simulate a modal dialog, with a message pump that is willing
		// to execute hiPriFactory messages but not loPriFactory messages.
		hiPriFactory.doModalLoopUntilEmpty();
		Assert.assertTrue(outer.isDone());
	}

	/**
	 * Verifies that each instance of JTF is only notified once of a nested {@link JoinableFuture}'s attempt to get to
	 * the UI thread.
	 */
	@Test
	public void testNestedFactoriesDoNotDuplicateEffortMixed() {
		ModalPumpingJoinableFutureFactory loPriFactory = new ModalPumpingJoinableFutureFactory(this.context);
		ModalPumpingJoinableFutureFactory hiPriFactory = new ModalPumpingJoinableFutureFactory(this.context);

		// In this particular test, we intentionally mix up the JTFs in hi-lo-hi-lo order.
		JoinableFuture<Void> outer = hiPriFactory.runAsync(() -> {
			return Async.awaitAsync(
				loPriFactory.runAsync(() -> {
					return Async.awaitAsync(
						hiPriFactory.runAsync(() -> {
							return Async.awaitAsync(
								loPriFactory.runAsync(() -> {
									return Async.awaitAsync(Async.yieldAsync());
								}));
						}));
				}));
		});

		// Verify that each factory received the message exactly once.
		Assert.assertEquals(1, Iterables.size(loPriFactory.getJoinableFuturesPendingMainthread()));
		Assert.assertEquals(1, Iterables.size(hiPriFactory.getJoinableFuturesPendingMainthread()));

		// Simulate a modal dialog, with a message pump that is willing
		// to execute hiPriFactory messages but not loPriFactory messages.
		hiPriFactory.doModalLoopUntilEmpty();
		Assert.assertTrue(outer.isDone());
	}

	@Test
	public void testNestedFactoriesCanBeCollected() {
		ModalPumpingJoinableFutureFactory outerFactory = new ModalPumpingJoinableFutureFactory(context);
		ModalPumpingJoinableFutureFactory innerFactory = new ModalPumpingJoinableFutureFactory(context);

		StrongBox<JoinableFuture<Void>> inner = new StrongBox<>();
		JoinableFuture<Void> outer = outerFactory.runAsync(() -> {
			inner.set(innerFactory.runAsync(() -> {
				return Async.awaitAsync(Async.yieldAsync());
			}));

			return Async.awaitAsync(inner.get());
		});

		outerFactory.doModalLoopUntilEmpty();
		Assume.assumeTrue("this is a product defect, but this test assumes this works to test something else.", outer.isDone());

		// Allow the dispatcher to drain all messages that may be holding references.
		SynchronizationContext.getCurrent().post(s -> testFrame.setContinue(false), null);
		pushFrame();

		// Now we verify that while 'inner' is non-null that it doesn't hold outerFactory in memory
		// once 'inner' has completed.
		WeakReference<ModalPumpingJoinableFutureFactory> weakOuterFactory = new WeakReference<>(outerFactory);
		outer = null;
		outerFactory = null;
		Runtime.getRuntime().gc();
		Assert.assertNull(weakOuterFactory.get());
	}

//        // This is a known issue and we haven't a fix yet
//        [StaFact(Skip = "Ignored")]
//        public void CallContextWasOverwrittenByReentrance()
//        {
//            var asyncLock = new AsyncReaderWriterLock();
//
//            // 4. This is the task which the UI thread is waiting for,
//            //    and it's scheduled on UI thread.
//            //    As UI thread did "Join" before "await", so this task can reenter UI thread.
//            var task = Task.Run(async delegate
//            {
//                await this.asyncPump.SwitchToMainThreadAsync();
//
//                // 4.1 Now this anonymous method is on UI thread,
//                //     and it needs to acquire a read lock.
//                //
//                //     The attempt to acquire a lock would lead to a deadlock!
//                //     Because the call context was overwritten by this reentrance,
//                //     this method didn't know the write lock was already acquired at
//                //     the bottom of the call stack. Therefore, it will issue a new request
//                //     to acquire the read lock. However, that request won't be completed as
//                //     the write lock holder is also waiting for this method to complete.
//                //
//                //     This test would be timeout here.
//                using (await asyncLock.ReadLockAsync())
//                {
//                }
//            });
//
//            this.asyncPump.Run(async delegate
//            {
//                // 1. Acquire write lock on worker thread
//                using (await asyncLock.WriteLockAsync())
//                {
//                    // 2. Hold the write lock but switch to UI thread.
//                    //    That's to simulate the scenario to call into IVs* services
//                    await this.asyncPump.SwitchToMainThreadAsync();
//
//                    // 3. Join and wait for another BG task.
//                    //    That's to simulate the scenario when the IVs* service also calls into CPS,
//                    //    and CPS join and wait for another task.
//                    using (this.joinableCollection.Join())
//                    {
//                        await task;
//                    }
//                }
//            });
//        }

	/**
	 * Rapidly posts messages to several interlinked AsyncPumps to check for thread-safety and deadlocks.
	 */
	@Test
	public void testPostStress() {
		AtomicInteger outstandingMessages = new AtomicInteger(0);
		CompletableFuture<Void> cancellationFuture = Async.delayAsync(1000, TimeUnit.MILLISECONDS);
		JoinableFutureCollection collection2 = asyncPump.getContext().createCollection();
		JoinableFutureFactory pump2 = asyncPump.getContext().createFactory(collection2);
		StrongBox<CompletableFuture<Void>> t1 = new StrongBox<>(null);
		StrongBox<CompletableFuture<Void>> t2 = new StrongBox<>(null);

		((DerivedJoinableFutureFactory)this.asyncPump).AssumeConcurrentUse = true;
		((DerivedJoinableFutureFactory)pump2).AssumeConcurrentUse = true;

		pump2.run(() -> {
			t1.set(Futures.runAsync(() -> {
				try (Disposable disposable = joinableCollection.join()) {
					while (!cancellationFuture.isDone()) {
						Awaiter<Void> awaiter = pump2.switchToMainThreadAsync().getAwaiter();
						outstandingMessages.incrementAndGet();
						awaiter.onCompleted(() -> {
							awaiter.getResult();
							if (outstandingMessages.decrementAndGet() == 0) {
								testFrame.setContinue(false);
							}
						});
					}
				}

				return Futures.completedNull();
			}));
			return Futures.completedNull();
		});

		asyncPump.run(() -> {
			t2.set(Futures.runAsync(() -> {
				try (Disposable disposable = collection2.join()) {
					while (!cancellationFuture.isDone()) {
						Awaiter<Void> awaiter = asyncPump.switchToMainThreadAsync().getAwaiter();
						outstandingMessages.incrementAndGet();
						awaiter.onCompleted(() -> {
							awaiter.getResult();
							if (outstandingMessages.decrementAndGet() == 0) {
								testFrame.setContinue(false);
							}
						});
					}
				}
			}));
			return Futures.completedNull();
		});

		pushFrame();
		t1.get().join();
		t2.get().join();
	}

	/**
	 * Verifies that in the scenario when the initializing thread doesn't have a sync context at all (vcupgrade.exe)
	 * that reasonable behavior still occurs.
	 */
	@Test
	public void testNoMainThreadSyncContextAndKickedOffFromOriginalThread() {
		SynchronizationContext.setSynchronizationContext(null);
		DerivedJoinableFutureContext context = new DerivedJoinableFutureContext();
		joinableCollection = context.createCollection();
		asyncPump = context.createFactory(joinableCollection);

		asyncPump.run(() -> {
			Assert.assertSame(originalThread, Thread.currentThread());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> Async.awaitAsync(
						Async.yieldAsync(),
						() -> Async.awaitAsync(
							ForkJoinPool.commonPool(),
							() -> Async.awaitAsync(
								Async.yieldAsync(),
								() -> Async.awaitAsync(
									asyncPump.switchToMainThreadAsync(),
									() -> Async.awaitAsync(
										Async.yieldAsync())))))));
		});

		JoinableFuture<Void> joinable = asyncPump.runAsync(() -> {
			Assert.assertSame(originalThread, Thread.currentThread());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					// verifies no yield
					Assert.assertTrue(asyncPump.switchToMainThreadAsync().getAwaiter().isDone());

					return Async.awaitAsync(
						asyncPump.switchToMainThreadAsync(),
						() -> Async.awaitAsync(
							Async.yieldAsync(),
							() -> Async.awaitAsync(
								ForkJoinPool.commonPool(),
								() -> Async.awaitAsync(
									Async.yieldAsync(),
									() -> Async.awaitAsync(
										asyncPump.switchToMainThreadAsync(),
										() -> Async.awaitAsync(
											Async.yieldAsync()))))));
				});
		});

		joinable.join();
	}

	/**
	 * Verifies that in the scenario when the initializing thread doesn't have a sync context at all (vcupgrade.exe)
	 * that reasonable behavior still occurs.
	 */
	@Test
	public void testNoMainThreadSyncContextAndKickedOffFromOtherThread() {
		SynchronizationContext.setSynchronizationContext(null);
		context = new DerivedJoinableFutureContext();
		joinableCollection = context.createCollection();
		asyncPump = context.createFactory(joinableCollection);
		StrongBox<Thread> otherThread = new StrongBox<>();

		Futures.runAsync(() -> {
			otherThread.set(Thread.currentThread());
			asyncPump.run(() -> {
				Assert.assertSame(otherThread.get(), Thread.currentThread());
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> {
						Assert.assertSame(otherThread.get(), Thread.currentThread());

						// verifies no yield
						Assert.assertTrue(asyncPump.switchToMainThreadAsync().getAwaiter().isDone());

						return Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(), // we expect this to no-op
							() -> {
								Assert.assertSame(otherThread.get(), Thread.currentThread());
								return Async.awaitAsync(
									Async.yieldAsync(),
									() -> {
										Assert.assertSame(otherThread.get(), Thread.currentThread());

										return Async.awaitAsync(Futures.runAsync(() -> {
											Thread threadpoolThread = Thread.currentThread();
											Assert.assertNotSame(otherThread.get(), Thread.currentThread());
											return Async.awaitAsync(
												Async.yieldAsync(),
												() -> {
													Assert.assertNotSame(otherThread.get(), Thread.currentThread());

													return Async.awaitAsync(
														asyncPump.switchToMainThreadAsync(),
														() -> Async.awaitAsync(Async.yieldAsync()));
												});
										}));
									});
							});
					});
			});

			JoinableFuture<Void> joinable = asyncPump.runAsync(() -> {
				Assert.assertSame(otherThread.get(), Thread.currentThread());
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> {
						// verifies no yield
						Assert.assertTrue(asyncPump.switchToMainThreadAsync().getAwaiter().isDone());

						return Async.awaitAsync(
							asyncPump.switchToMainThreadAsync(), // we expect this to no-op
							() -> Async.awaitAsync(
								Async.yieldAsync(),
								() -> Async.awaitAsync(
									Futures.runAsync(() -> {
										Thread threadpoolThread = Thread.currentThread();
										return Async.awaitAsync(
											Async.yieldAsync(),
											() -> Async.awaitAsync(
												asyncPump.switchToMainThreadAsync(),
												() -> Async.awaitAsync(
													Async.yieldAsync())));
									}))));
					});
			});
			joinable.join();
		}).join();
	}

	@Test
	public void testMitigationAgainstBadSyncContextOnMainThread() {
		SynchronizationContext ordinarySyncContext = new SynchronizationContext();
		SynchronizationContext.setSynchronizationContext(ordinarySyncContext);
//            var assertDialogListener = Trace.Listeners.OfType<DefaultTraceListener>().FirstOrDefault();
//            assertDialogListener.AssertUiEnabled = false;
		asyncPump.runAsync(() -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> Async.awaitAsync(
					asyncPump.switchToMainThreadAsync()));
		});
//            assertDialogListener.AssertUiEnabled = true;
	}

	//[StaFact, Trait("Stress", "true"), Trait("TestCategory", "FailsInCloudTest"), Trait("FailsInLocalBatch", "true")]
	@Test
	@Ignore("GC test is unstable")
	public void testSwitchToMainThreadMemoryLeak() {
		checkGCPressure(
			() -> {
				return Async.awaitAsync(
					ForkJoinPool.commonPool(),
					() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync(CancellationToken.none())));
			},
			7223); // NOTE: .NET has this at 2615
	}

	//[StaFact, Trait("Stress", "true"), Trait("TestCategory", "FailsInCloudTest"), Trait("FailsInLocalBatch", "true")]
	@Test
	@Ignore("GC test is unstable")
	public void testSwitchToMainThreadMemoryLeakWithCancellationToken() {
		CancellationTokenSource tokenSource = new CancellationTokenSource();
		checkGCPressure(
			() -> {
				return Async.awaitAsync(
					ForkJoinPool.commonPool(),
					() -> Async.awaitAsync(asyncPump.switchToMainThreadAsync(tokenSource.getToken())));
			},
			7807); // NOTE: .NET has this at 2800
	}

	@Test
	public void testSwitchToMainThreadSucceedsWhenConstructedUnderMTAOperation() {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			JoinableFutureCollection otherCollection = context.createCollection();
			JoinableFutureFactory otherPump = context.createFactory(otherCollection);
			return Async.finallyAsync(
				Async.awaitAsync(
					otherPump.switchToMainThreadAsync(),
					() -> {
						Assert.assertSame(originalThread, Thread.currentThread());
						return Futures.completedNull();
					}),
				() -> {
					testFrame.setContinue(false);
				});
		});

		pushFrame();
		// rethrow any failures
		task.join();
	}

	//[StaFact, Trait("GC", "true")]
	@Test
	public void testJoinableFutureReleasedBySyncContextAfterCompletion() {
		StrongBox<SynchronizationContext> syncContext = new StrongBox<>();
		WeakReference<JoinableFuture<Void>> job = new WeakReference<>(asyncPump.runAsync(() -> {
			// simulate someone who has captured the sync context.
			syncContext.set(SynchronizationContext.getCurrent());
			return Futures.completedNull();
		}));

		// We intentionally still have a reference to the SyncContext that represents the task.
		// We want to make sure that even with that, the JoinableTask itself can be collected.
		Runtime.getRuntime().gc();
		Assert.assertNull(job.get());
	}

	@Test
	public void testJoinTwice() {
		JoinableFuture<Void> joinable = asyncPump.runAsync(() -> Async.awaitAsync(Async.yieldAsync()));

		asyncPump.run(() -> {
			CompletableFuture<Void> task1 = joinable.joinAsync();
			CompletableFuture<Void> task2 = joinable.joinAsync();
			return Async.awaitAsync(CompletableFuture.allOf(task1, task2));
		});
	}

	@Test
	public void testGrandparentJoins() {
		JoinableFuture<Void> innerJoinable = asyncPump.runAsync(() -> Async.awaitAsync(Async.yieldAsync()));

		JoinableFuture<Void> outerJoinable = asyncPump.runAsync(() -> Async.awaitAsync(innerJoinable));

		outerJoinable.join();
	}

	//[StaFact, Trait("GC", "true"), Trait("TestCategory", "FailsInCloudTest")]
	@Test
	@Ignore("GC test is unstable")
	public void testRunSynchronouslyFutureNoYieldGCPressure() {
		this.checkGCPressure(() -> {
			asyncPump.run(() -> {
				return Futures.completedNull();
			});
		}, /*maxBytesAllocated:*/ 1037); // Note: .NET has this at 573
	}

//        [StaFact, Trait("GC", "true"), Trait("TestCategory", "FailsInCloudTest")]
//        public void RunSynchronouslyTaskOfTNoYieldGCPressure()
//        {
//            Task<object> completedTask = Task.FromResult<object>(null);
//
//            this.CheckGCPressure(delegate
//            {
//                this.asyncPump.Run(delegate
//                {
//                    return completedTask;
//                });
//            }, maxBytesAllocated: 572);
//        }

	//[StaFact, Trait("GC", "true"), Trait("TestCategory", "FailsInCloudTest"), Trait("FailsInLocalBatch", "true")]
	@Test
	@Ignore("GC test is unstable")
	public void testRunSynchronouslyTaskWithYieldGCPressure() {
		checkGCPressure(() -> {
			asyncPump.run(() -> {
				return Async.awaitAsync(Async.yieldAsync());
			});
		}, /*maxBytesAllocated:*/ 2867); // Note: .NET has this at 1800
	}

//        [StaFact, Trait("GC", "true"), Trait("TestCategory", "FailsInCloudTest"), Trait("FailsInLocalBatch", "true")]
//        public void RunSynchronouslyTaskOfTWithYieldGCPressure()
//        {
//            Task<object> completedTask = Task.FromResult<object>(null);
//
//            this.CheckGCPressure(delegate
//            {
//                this.asyncPump.Run(async delegate
//                {
//                    await Task.Yield();
//                });
//            }, maxBytesAllocated: 1800);
//        }

	/**
	 * Verifies that when two AsyncPumps are stacked on the main thread by (unrelated) COM reentrancy that the bottom
	 * one doesn't "steal" the work before the inner one can when the outer one isn't on the top of the stack and
	 * therefore can't execute it anyway, thereby precluding the inner one from executing it either and leading to
	 * deadlock.
	 */
	@Test
	public void testNestedRunSynchronouslyOuterDoesNotStealWorkFromNested() {
		JoinableFutureCollection collection = context.createCollection();
		COMReentrantJoinableFutureFactory asyncPump = new COMReentrantJoinableFutureFactory(collection);
		AsyncManualResetEvent nestedWorkBegun = new AsyncManualResetEvent();
		asyncPump.reenterWaitWith(() -> {
			asyncPump.run(() -> {
				return Async.awaitAsync(Async.yieldAsync());
			});

			nestedWorkBegun.set();
		});

		asyncPump.run(() -> {
			return Async.awaitAsync(nestedWorkBegun);
		});
	}

//        [StaFact]
//        public void RunAsyncExceptionsCapturedInResult()
//        {
//            var exception = new InvalidOperationException();
//            var joinableTask = this.asyncPump.RunAsync(delegate
//            {
//                throw exception;
//            });
//            Assert.True(joinableTask.IsCompleted);
//            Assert.Same(exception, joinableTask.Task.Exception.InnerException);
//            var awaiter = joinableTask.GetAwaiter();
//            try
//            {
//                awaiter.GetResult();
//                Assert.True(false, "Expected exception not rethrown.");
//            }
//            catch (InvalidOperationException ex)
//            {
//                Assert.Same(ex, exception);
//            }
//        }
//
//        [StaFact]
//        public void RunAsyncOfTExceptionsCapturedInResult()
//        {
//            var exception = new InvalidOperationException();
//            var joinableTask = this.asyncPump.RunAsync<int>(delegate
//            {
//                throw exception;
//            });
//            Assert.True(joinableTask.IsCompleted);
//            Assert.Same(exception, joinableTask.Task.Exception.InnerException);
//            var awaiter = joinableTask.GetAwaiter();
//            try
//            {
//                awaiter.GetResult();
//                Assert.True(false, "Expected exception not rethrown.");
//            }
//            catch (InvalidOperationException ex)
//            {
//                Assert.Same(ex, exception);
//            }
//        }
//
//        [StaFact]
//        [Trait("TestCategory", "FailsInCloudTest")] // see https://github.com/Microsoft/vs-threading/issues/46
//        public void RunAsyncWithNonYieldingDelegateNestedInRunOverhead()
//        {
//            var waitCountingJTF = new WaitCountingJoinableTaskFactory(this.asyncPump.Context);
//            waitCountingJTF.Run(async delegate
//            {
//                await TaskScheduler.Default;
//                for (int i = 0; i < 1000; i++)
//                {
//                    // TIP: spinning gives the blocking thread longer to wake up, which
//                    // if it wakes up at all tends to mean it will wake up in time for more
//                    // of the iterations, showing that doing real work exercerbates the problem.
//                    ////for (int j = 0; j < 5000; j++) { }
//
//                    await this.asyncPump.RunAsync(delegate { return TplExtensions.CompletedTask; });
//                }
//            });
//
//            // We assert that since the blocking thread didn't need to wake up at all,
//            // it should have only slept once. Any more than that constitutes unnecessary overhead.
//            Assert.Equal(1, waitCountingJTF.WaitCount);
//        }
//
//        [StaFact]
//        [Trait("TestCategory", "FailsInCloudTest")] // see https://github.com/Microsoft/vs-threading/issues/45
//        public void RunAsyncWithYieldingDelegateNestedInRunOverhead()
//        {
//            var waitCountingJTF = new WaitCountingJoinableTaskFactory(this.asyncPump.Context);
//            waitCountingJTF.Run(async delegate
//            {
//                await TaskScheduler.Default;
//                for (int i = 0; i < 1000; i++)
//                {
//                    // TIP: spinning gives the blocking thread longer to wake up, which
//                    // if it wakes up at all tends to mean it will wake up in time for more
//                    // of the iterations, showing that doing real work exercerbates the problem.
//                    ////for (int j = 0; j < 5000; j++) { }
//
//                    await this.asyncPump.RunAsync(async delegate { await Task.Yield(); });
//                }
//            });
//
//            // We assert that since the blocking thread didn't need to wake up at all,
//            // it should have only slept once. Any more than that constitutes unnecessary overhead.
//            Assert.Equal(1, waitCountingJTF.WaitCount);
//        }
//
//        [StaFact]
//        public void SwitchToMainThreadShouldNotLeakJoinableTaskWhenGetResultRunsFirst()
//        {
//            var cts = new CancellationTokenSource();
//            var factory = (DerivedJoinableTaskFactory)this.asyncPump;
//            var transitionedToMainThread = new ManualResetEventSlim(false);
//            factory.PostToUnderlyingSynchronizationContextCallback = () =>
//            {
//                // Pause the background thread after posted the continuation to JoinableTask.
//                transitionedToMainThread.Wait();
//            };
//
//            object result = new object();
//            WeakReference<object> weakResult = new WeakReference<object>(result);
//
//            this.asyncPump.Run(async () =>
//            {
//                // Needs to switch to background thread at first in order to test the code that requests switch to main thread.
//                await TaskScheduler.Default;
//
//                // This nested run starts on background thread and then requests to switch to main thread.
//                // The remaining parts in the async delegate would be executed on main thread. This nested run
//                // will complete only when both the background thread works (aka. MainThreadAWaiter.OnCompleted())
//                // and the main thread works are done, and then we could start verification.
//                this.asyncPump.Run(async () =>
//            {
//                await this.asyncPump.SwitchToMainThreadAsync(cts.Token);
//
//                // Resume the background thread after transitioned to main thread.
//                // This is to ensure the timing that GetResult() must be called before OnCompleted() registers the cancellation.
//                transitionedToMainThread.Set();
//                return result;
//            });
//            });
//
//            // Needs to give the dispatcher a chance to run the posted action in order to release
//            // the last reference to the JoinableTask.
//            this.PushFrameTillQueueIsEmpty();
//
//            result = null;
//            GC.Collect();
//
//            object target;
//            weakResult.TryGetTarget(out target);
//            Assert.Null(target); //, "The task's result should be collected unless the JoinableTask is leaked");
//        }
//
//        [StaFact]
//        public void SwitchToMainThreadShouldNotLeakJoinableTaskWhenGetResultRunsLater()
//        {
//            var cts = new CancellationTokenSource();
//            var factory = (DerivedJoinableTaskFactory)this.asyncPump;
//            var waitForOnCompletedIsFinished = new ManualResetEventSlim(false);
//            factory.TransitionedToMainThreadCallback = (jt) =>
//            {
//                // Pause the main thread before execute the continuation.
//                waitForOnCompletedIsFinished.Wait();
//            };
//
//            object result = new object();
//            WeakReference<object> weakResult = new WeakReference<object>(result);
//
//            this.asyncPump.Run(async () =>
//            {
//                // Needs to switch to background thread at first in order to test the code that requests switch to main thread.
//                await TaskScheduler.Default;
//
//                // This nested async run starts on background thread and then requests to switch to main thread.
//                // It will complete only when the background thread works (aka. MainThreadAWaiter.OnCompleted()) are done,
//                // and then we will signal a test event to resume the main thread execution, to let the remaining parts
//                // in the async delegate go through.
//                var joinable = this.asyncPump.RunAsync(async () =>
//            {
//                await this.asyncPump.SwitchToMainThreadAsync(cts.Token);
//                return result;
//            });
//
//                // Resume the main thread after OnCompleted() finishes.
//                // This is to ensure the timing that GetResult() must be called after OnCompleted() is fully done.
//                waitForOnCompletedIsFinished.Set();
//                await joinable;
//            });
//
//            // Needs to give the dispatcher a chance to run the posted action in order to release
//            // the last reference to the JoinableTask.
//            this.PushFrameTillQueueIsEmpty();
//
//            result = null;
//            GC.Collect();
//
//            object target;
//            weakResult.TryGetTarget(out target);
//            Assert.Null(target); // The task's result should be collected unless the JoinableTask is leaked
//        }

	/**
	 * Executes background work where the {@link JoinableFuture}'s {@link SynchronizationContext} adds work to the
	 * threadpoolQueue but doesn't give it a chance to run while the parent {@link JoinableFuture} lasts.
	 *
	 * <p>
	 * Repro for bug 245563:
	 * https://devdiv.visualstudio.com/web/wi.aspx?pcguid=011b8bdf-6d56-4f87-be0d-0092136884d9&id=245563</p>
	 */
	@Test
	@Ignore("Fails for Java")
	public void testUnawaitedBackgroundWorkShouldComplete() {
		AtomicBoolean unawaitedWorkCompleted = new AtomicBoolean(false);
		Supplier<CompletableFuture<Void>> otherAsyncMethod = () -> {
			return Async.awaitAsync(
				// this posts to the JoinableTask.threadPoolQueue
				Async.yieldAsync(),
				() -> Async.awaitAsync(
					// this should schedule directly to the .NET ThreadPool.
					Async.yieldAsync(),
					() -> {
						unawaitedWorkCompleted.set(true);
						return Futures.completedNull();
					}));
		};
		AsyncManualResetEvent jtStarted = new AsyncManualResetEvent();
		StrongBox<CompletableFuture<Void>> unawaitedWork = new StrongBox<>(null);
		CompletableFuture<Void> bkgrndThread = Futures.runAsync(() -> {
			asyncPump.run(() -> {
				jtStarted.set();
				unawaitedWork.set(otherAsyncMethod.get());
				return Futures.completedNull();
			});
		});
		context.getFactory().run(() -> {
			return Async.awaitAsync(
				jtStarted,
				() -> {
					CompletableFuture<Void> joinTask = joinableCollection.joinUntilEmptyAsync();
					return Async.awaitAsync(
						TplExtensions.withTimeout(joinTask, UNEXPECTED_TIMEOUT, UNEXPECTED_TIMEOUT_UNIT),
						() -> {
							Assert.assertTrue(joinTask.isDone());
							return Async.awaitAsync(unawaitedWork.get());
						});
				});
		});
		Assert.assertTrue(unawaitedWorkCompleted.get());
	}

	@Test
	@Ignore("Failing for Java only")
	public void testUnawaitedBackgroundWorkShouldCompleteWithoutSyncBlock() throws Exception {
		CompletableFuture<Void> unawaitedWorkCompleted = new CompletableFuture<>();
		Supplier<CompletableFuture<Void>> otherAsyncMethod = ExecutionContext.wrap(() -> {
			StrongBox<CompletableFuture<Void>> result = new StrongBox<>();
			Consumer<Void> implementation = ignored -> result.set(Async.awaitAsync(
				// this posts to the JoinableTask.threadPoolQueue
				Async.yieldAsync(),
				() -> Async.awaitAsync(
					// this should schedule directly to the .NET ThreadPool.
					Async.yieldAsync(),
					() -> {
						unawaitedWorkCompleted.complete(null);
						return Futures.completedNull();
					})));

			ExecutionContext.run(ExecutionContext.capture(), implementation, null);
			return result.get();
		});

		CompletableFuture<Void> bkgrndThread = Futures.runAsync(() -> {
			asyncPump.run(() -> {
				TplExtensions.forget(otherAsyncMethod.get());
				return Futures.completedNull();
			});
		});

		bkgrndThread.join();
		unawaitedWorkCompleted.get(EXPECTED_TIMEOUT, EXPECTED_TIMEOUT_UNIT);
	}

	@Test
	@Ignore("Fails for Java")
	public void testUnawaitedBackgroundWorkShouldCompleteAndNotCrashWhenThrown() {
		Supplier<CompletableFuture<Void>> otherAsyncMethod = () -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					throw new RuntimeException("This shouldn't crash, since it was fire and forget.");
				});
		};
		CompletableFuture<Void> bkgrndThread = Futures.runAsync(() -> {
			asyncPump.run(() -> {
				TplExtensions.forget(otherAsyncMethod.get());
				return Futures.completedNull();
			});
		});
		context.getFactory().run(() -> {
			CompletableFuture<Void> joinTask = this.joinableCollection.joinUntilEmptyAsync();
			return Async.awaitAsync(
				TplExtensions.withTimeout(joinTask, UNEXPECTED_TIMEOUT, UNEXPECTED_TIMEOUT_UNIT),
				() -> {
					Assert.assertTrue(joinTask.isDone());
					return Futures.completedNull();
				});
		});
	}

	@Test
	public void testPostToUnderlyingSynchronizationContextShouldBeAfterSignalJoinableTasks() {
		DerivedJoinableFutureFactory factory = (DerivedJoinableFutureFactory)this.asyncPump;
		CompletableFuture<?> transitionedToMainThread = new CompletableFuture<>();
		factory.PostToUnderlyingSynchronizationContextCallback = () -> {
			// The JoinableTask should be wakened up and the code to set this event should be executed on main thread,
			// otherwise, this wait will cause test timeout.
			transitionedToMainThread.join();
		};
		asyncPump.run(() -> {
			return Async.awaitAsync(
				ForkJoinPool.commonPool(),
				() -> Async.awaitAsync(
					asyncPump.switchToMainThreadAsync(),
					() -> {
						transitionedToMainThread.complete(null);
						return Futures.completedNull();
					}));
		});
	}

	@NotNull
	@Override
	protected JoinableFutureContext createJoinableFutureContext() {
		return new DerivedJoinableFutureContext();
	}

	private static void someFireAndForgetMethod() {
		TplExtensions.forget(Async.awaitAsync(Async.yieldAsync()));
	}

	@NotNull
	private CompletableFuture<Void> someOperationThatMayBeOnMainThreadAsync() {
		return Async.awaitAsync(
			Async.yieldAsync(),
			() -> Async.awaitAsync(Async.yieldAsync()));
	}

	private CompletableFuture<Void> someOperationThatUsesMainThreadViaItsOwnAsyncPumpAsync() {
		JoinableFutureCollection otherCollection = this.context.createCollection();
		JoinableFutureFactory privateAsyncPump = this.context.createFactory(otherCollection);
		return Futures.runAsync(() -> {
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					return Async.awaitAsync(
						privateAsyncPump.switchToMainThreadAsync(),
						() -> Async.awaitAsync(Async.yieldAsync()));
				});
		});
	}

	private CompletableFuture<?> testReentrancyOfUnrelatedDependentWork() {
		AsyncManualResetEvent unrelatedMainThreadWorkWaiting = new AsyncManualResetEvent();
		AsyncManualResetEvent unrelatedMainThreadWorkInvoked = new AsyncManualResetEvent();
		JoinableFutureCollection unrelatedCollection;
		JoinableFutureFactory unrelatedPump;
		CompletableFuture<?> unrelatedTask;

		// don't let this task be identified as related to the caller, so that the caller has to Join for this to complete.
		try (RevertRelevance disposable = context.suppressRelevance()) {
			unrelatedCollection = this.context.createCollection();
			unrelatedPump = this.context.createFactory(unrelatedCollection);
			unrelatedTask = Futures.runAsync(() -> {
				return Async.awaitAsync(
					TestUtilities.yieldAndNotify(unrelatedPump.switchToMainThreadAsync().getAwaiter(), unrelatedMainThreadWorkWaiting, unrelatedMainThreadWorkInvoked),
					() -> {
						Assert.assertSame(originalThread, Thread.currentThread());
						return Futures.completedNull();
					});
			});
		}

		return Async.awaitAsync(
			unrelatedMainThreadWorkWaiting.waitAsync(),
			() -> {
				// Await an extra bit of time to allow for unexpected reentrancy to occur while the
				// main thread is only synchronously blocking.
				CompletableFuture<Void> waitTask = unrelatedMainThreadWorkInvoked.waitAsync();
				return Async.awaitAsync(
					Async.whenAny(waitTask, Async.delayAsync(ASYNC_DELAY / 2, ASYNC_DELAY_UNIT)),
					firstToComplete -> {
						Assert.assertNotSame("Background work completed work on the UI thread before it was invited to do so.", waitTask, firstToComplete);
						return Async.usingAsync(
							unrelatedCollection.join(),
							() -> {
								// The work SHOULD be able to complete now that we've Joined the work.
								return Async.awaitAsync(
									waitTask,
									() -> {
										Assert.assertSame(originalThread, Thread.currentThread());
										return Futures.completedNull();
									});
							});
					});
			});
	}

	private void runFuncOfTaskHelper() {
		Thread initialThread = Thread.currentThread();
		asyncPump.run(() -> {
			Assert.assertSame(initialThread, Thread.currentThread());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertSame(initialThread, Thread.currentThread());
					return Futures.completedNull();
				});
		});
	}

	private void runFuncOfTaskOfTHelper() {
		Thread initialThread = Thread.currentThread();
		GenericParameterHelper expectedResult = new GenericParameterHelper();
		GenericParameterHelper actualResult = asyncPump.run(() -> {
			Assert.assertSame(initialThread, Thread.currentThread());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertSame(initialThread, Thread.currentThread());
					return CompletableFuture.completedFuture(expectedResult);
				});
		});

		Assert.assertSame(expectedResult, actualResult);
	}

	/**
	 * Writes out a DGML graph of pending futures and collections to the test context.
	 */
	private void printActiveFuturesReport() {
		printActiveFuturesReport(null);
	}

	/**
	 * Writes out a DGML graph of pending futures and collections to the test context.
	 *
	 * @param context A specific context to collect data from; {@code null} will use this context.
	 */
	private void printActiveFuturesReport(@Nullable JoinableFutureContext context) {
		context = context != null ? context : this.context;
		HangReportContributor contributor = context;
		HangReportContribution report = contributor.getHangReport();
		System.out.println("DGML task graph");
		System.out.println(report.getContent());
	}

	/**
	 * Simulates COM message pump reentrancy causing some unrelated work to "pump in" on top of a synchronously blocking
	 * wait.
	 */
	private static class COMReentrantJoinableFutureFactory extends JoinableFutureFactory {

		private Runnable runnable;

		COMReentrantJoinableFutureFactory(JoinableFutureContext context) {
			super(context);
		}

		COMReentrantJoinableFutureFactory(JoinableFutureCollection collection) {
			super(collection);
		}

		final void reenterWaitWith(Runnable runnable) {
			this.runnable = runnable;
		}

		@Override
		protected void waitSynchronously(CompletableFuture<?> future) {
			if (this.runnable != null) {
				Runnable action = this.runnable;
				this.runnable = null;
				action.run();
			}

			super.waitSynchronously(future);
		}
	}

	private static class WaitCountingJoinableFutureFactory extends JoinableFutureFactory {

		private final AtomicInteger waitCount = new AtomicInteger();

		WaitCountingJoinableFutureFactory(JoinableFutureContext owner) {
			super(owner);
		}

		final int getWaitCount() {
			return waitCount.get();
		}

		@Override
		protected void waitSynchronously(CompletableFuture<?> future) {
			waitCount.incrementAndGet();
			super.waitSynchronously(future);
		}
	}

	private static class DerivedJoinableFutureContext extends JoinableFutureContext {
		@NotNull
		@Override
		public JoinableFutureFactory createFactory(JoinableFutureCollection collection) {
			return new DerivedJoinableFutureFactory(collection);
		}
	}

	private static class DerivedJoinableFutureFactory extends JoinableFutureFactory {

		private final Set<JoinableFuture<?>> transitioningFutures = new HashSet<>();
		private final AtomicInteger transitioningToMainThreadHitCount = new AtomicInteger();
		private final AtomicInteger transitionedToMainThreadHitCount = new AtomicInteger();

		private JoinableFutureCollection transitioningTasksCollection;

		boolean AssumeConcurrentUse;

		Consumer<JoinableFuture<?>> TransitioningToMainThreadCallback;

		Consumer<JoinableFuture<?>> TransitionedToMainThreadCallback;

		Runnable PostToUnderlyingSynchronizationContextCallback;

		DerivedJoinableFutureFactory(JoinableFutureContext context) {
			super(context);
			this.transitioningTasksCollection = new JoinableFutureCollection(context, /*refCountAddedJobs:*/ true);
		}

		DerivedJoinableFutureFactory(JoinableFutureCollection collection) {
			super(collection);
			this.transitioningTasksCollection = new JoinableFutureCollection(collection.getContext(), /*refCountAddedJobs:*/ true);
		}

		final int getTransitionedToMainThreadHitCount() {
			return transitionedToMainThreadHitCount.get();
		}

		final int getTransitioningToMainThreadHitCount() {
			return transitioningToMainThreadHitCount.get();
		}

		final int getTransitioningTasksCount() {
			return Iterables.size(transitioningTasksCollection);
		}

		@Override
		protected void onTransitioningToMainThread(JoinableFuture<?> joinableFuture) {
			super.onTransitioningToMainThread(joinableFuture);
			Assert.assertFalse("A future should not be completed until at least the transition has completed.", joinableFuture.isDone());
			transitioningToMainThreadHitCount.incrementAndGet();

			this.transitioningTasksCollection.add(joinableFuture);

			// These statements and assertions assume that the test does not have jobs that execute code concurrently.
			synchronized (transitioningFutures) {
				Assert.assertTrue(transitioningFutures.add(joinableFuture) || AssumeConcurrentUse);
			}

			if (!AssumeConcurrentUse) {
				Assert.assertEquals("Imbalance of transition events.", getTransitionedToMainThreadHitCount() + 1, getTransitioningToMainThreadHitCount());
			}

			if (TransitioningToMainThreadCallback != null) {
				TransitioningToMainThreadCallback.accept(joinableFuture);
			}
		}

		@Override
		protected void onTransitionedToMainThread(JoinableFuture<?> joinableFuture, boolean canceled) {
			super.onTransitionedToMainThread(joinableFuture, canceled);
			transitionedToMainThreadHitCount.incrementAndGet();

			transitioningTasksCollection.remove(joinableFuture);

			if (canceled) {
				Assert.assertNotSame("A canceled transition should not complete on the main thread.", getContext().getMainThread(), Thread.currentThread());
				Assert.assertFalse(getContext().isOnMainThread());
			} else {
				Assert.assertSame("We should be on the main thread if we've just transitioned.", getContext().getMainThread(), Thread.currentThread());
				Assert.assertTrue(getContext().isOnMainThread());
			}

			// These statements and assertions assume that the test does not have jobs that execute code concurrently.
			synchronized (transitioningFutures) {
				Assert.assertTrue(transitioningFutures.remove(joinableFuture) || AssumeConcurrentUse);
			}

			if (!AssumeConcurrentUse) {
				Assert.assertEquals("Imbalance of transition events.", getTransitionedToMainThreadHitCount(), getTransitioningToMainThreadHitCount());
			}

			if (TransitionedToMainThreadCallback != null) {
				TransitionedToMainThreadCallback.accept(joinableFuture);
			}
		}

		@Override
		protected void waitSynchronously(CompletableFuture<?> future) {
			Assert.assertNotNull(future);
			super.waitSynchronously(future);
		}

		@Override
		protected <T> void postToUnderlyingSynchronizationContext(Consumer<T> callback, T state) {
			Assert.assertNotNull(this.getUnderlyingSynchronizationContext());
			Assert.assertNotNull(callback);
			Assert.assertTrue(SingleThreadedSynchronizationContext.isSingleThreadedSyncContext(this.getUnderlyingSynchronizationContext()));
			super.postToUnderlyingSynchronizationContext(callback, state);
			if (PostToUnderlyingSynchronizationContextCallback != null) {
				PostToUnderlyingSynchronizationContextCallback.run();
			}
		}
	}

	private static class ModalPumpingJoinableFutureFactory extends JoinableFutureFactory {

		private final ConcurrentLinkedQueue<Runnable> queuedMessages = new ConcurrentLinkedQueue<>();

		ModalPumpingJoinableFutureFactory(JoinableFutureContext context) {
			super(context);
		}

		final Iterable<Runnable> getJoinableFuturesPendingMainthread() {
			return this.queuedMessages;
		}

		/**
		 * Executes all work posted to this factory.
		 */
		final void doModalLoopUntilEmpty() {
			Runnable work;
			while ((work = queuedMessages.poll()) != null) {
				work.run();
			}
		}

		@Override
		protected <T> void postToUnderlyingSynchronizationContext(Consumer<T> callback, T state) {
			queuedMessages.add(() -> callback.accept(state));
			super.postToUnderlyingSynchronizationContext(callback, state);
		}
	}

	private static class MockAsyncService {

		private JoinableFutureCollection joinableCollection;
		private JoinableFutureFactory pump;
		private AsyncManualResetEvent stopRequested = new AsyncManualResetEvent();
		private Thread originalThread = Thread.currentThread();
		private CompletableFuture<Void> dependentTask;
		private MockAsyncService dependentService;

		MockAsyncService(JoinableFutureContext context) {
			this(context, null);
		}

		MockAsyncService(@NotNull JoinableFutureContext context, @Nullable MockAsyncService dependentService) {
			this.joinableCollection = context.createCollection();
			this.pump = context.createFactory(joinableCollection);
			this.dependentService = dependentService;
		}

		@NotNull
		final CompletableFuture<Void> operationAsync() {
			return Async.awaitAsync(
				pump.switchToMainThreadAsync(),
				() -> {
					CompletableFuture<Void> dependentOperation = Futures.completedNull();
					if (this.dependentService != null) {
						this.dependentTask = this.dependentService.operationAsync();
						dependentOperation = dependentTask;
					}

					return Async.awaitAsync(
						dependentOperation,
						() -> Async.awaitAsync(
							stopRequested.waitAsync(),
							() -> Async.awaitAsync(
								Async.yieldAsync(),
								() -> {
									Assert.assertSame(originalThread, Thread.currentThread());
									return Futures.completedNull();
								})));
				});
		}

		@NotNull
		final CompletableFuture<Void> stopAsync(@NotNull CompletableFuture<Void> operation) {
			Requires.notNull(operation, "operation");

			CompletableFuture<Void> dependentOperation = Futures.completedNull();
			if (dependentService != null) {
				dependentOperation = dependentService.stopAsync(dependentTask);
			}

			return Async.awaitAsync(
				dependentOperation,
				() -> {
					stopRequested.set();
					return Async.usingAsync(
						joinableCollection.join(),
						() -> Async.awaitAsync(operation));
				});
		}
	}
}
