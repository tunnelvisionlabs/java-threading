// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFutureContext.HangDetails;
import com.tunnelvisionlabs.util.validation.NotNull;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.w3c.dom.Document;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureContextTest extends JoinableFutureTestBase {
	@NotNull
	private JoinableFutureFactoryDerived getFactory() {
		return (JoinableFutureFactoryDerived)this.asyncPump;
	}

	@NotNull
	private JoinableFutureContextDerived getContext() {
		return (JoinableFutureContextDerived)this.context;
	}

	@Test
	public void testIsWithinJoinableFuture() {
		Assert.assertFalse(getContext().isWithinJoinableFuture());
		getFactory().run(() -> {
			Assert.assertTrue(getContext().isWithinJoinableFuture());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertTrue(getContext().isWithinJoinableFuture());
					return Async.awaitAsync(
						Futures.runAsync(() -> {
							Assert.assertTrue(getContext().isWithinJoinableFuture());
						}));
				});
		});
	}

	@Test
	public void testReportHangOnRun() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		CompletableFuture<Void> releaseTaskSource = new CompletableFuture<>();
		AsyncQueue<HangDetails> hangQueue = new AsyncQueue<>();
		getContext().onReportHang = (hangDuration, iterations, id)
			-> hangQueue.add(new HangDetails(hangDuration, iterations, id, null));

		Futures.runAsync(() -> {
			CancellationTokenSource cancellationTokenSource = new CancellationTokenSource(TEST_TIMEOUT);
			try {
				StrongBox<Duration> lastDuration = new StrongBox<>(Duration.ZERO);
				StrongBox<Integer> lastIteration = new StrongBox<>(0);
				StrongBox<UUID> lastId = new StrongBox<>(new UUID(0, 0));
				return Async.forAsync(
					() -> 0,
					i -> i < 3,
					i -> i + 1,
					i -> {
						return Async.awaitAsync(
							hangQueue.pollAsync(cancellationTokenSource.getToken()),
							tuple -> {
								Duration duration = tuple.getHangDuration();
								int iterations = tuple.getNotificationCount();
								UUID id = tuple.getHangId();
								Assert.assertTrue(lastDuration.value == Duration.ZERO || lastDuration.value.compareTo(duration) < 0);
								Assert.assertEquals(lastIteration.value + 1, iterations);
								Assert.assertNotEquals(new UUID(0, 0), id);
								Assert.assertTrue(lastId.value.equals(new UUID(0, 0)) || lastId.value.equals(id));
								lastDuration.value = duration;
								lastIteration.value = iterations;
								lastId.value = id;
								return Futures.completedNull();
							});
					})
					.handle((result, exception) -> {
						if (exception != null) {
							releaseTaskSource.completeExceptionally(exception);
						} else {
							releaseTaskSource.complete(null);
						}
						return null;
					});
			} catch (Throwable ex) {
				releaseTaskSource.completeExceptionally(ex);
				return Futures.completedNull();
			}
		});

		getFactory().run(() -> Async.awaitAsync(releaseTaskSource));
	}

	@Test
	public void testNoReportHangOnRunAsync() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		JoinableFuture<?> joinableTask = getFactory().runAsync(
			() -> Async.delayAsync(getFactory().getHangDetectionTimeout().multipliedBy(3)));

		// don't use JoinableFuture.join, since we're trying to simulate runAsync not becoming synchronous.
		joinableTask.getFuture().join();
		Assert.assertFalse(hangReported.get());
	}

	@Test
	public void testReportHangOnRunAsyncThenJoin() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		CompletableFuture<Void> releaseTaskSource = new CompletableFuture<>();
		AsyncQueue<Duration> hangQueue = new AsyncQueue<>();
		getContext().onReportHang = (hangDuration, iterations, id) -> hangQueue.add(hangDuration);

		TplExtensions.forget(Futures.supplyAsync(() -> {
			CancellationTokenSource ct = new CancellationTokenSource(TEST_TIMEOUT);
			return Async.awaitAsync(
				Futures.completedNull(),
				() -> {
					StrongBox<Duration> lastDuration = new StrongBox<>(Duration.ZERO);
					return Async.awaitAsync(
						Async.forAsync(
							() -> 0,
							i -> i < 3,
							i -> i + 1,
							i -> Async.awaitAsync(
								hangQueue.pollAsync(ct.getToken()),
								duration -> {
									Assert.assertTrue(lastDuration.value == Duration.ZERO || lastDuration.value.compareTo(duration) < 0);
									lastDuration.value = duration;
									return Futures.completedNull();
								})),
						() -> {
							releaseTaskSource.complete(null);
							return Futures.completedNull();
						});
				})
				.handle((result, exception) -> {
					if (exception != null) {
						releaseTaskSource.completeExceptionally(exception);
					}

					return Futures.completedNull();
				});
		}));

		JoinableFuture<Void> joinableTask = getFactory().runAsync(() -> Async.awaitAsync(releaseTaskSource));
		joinableTask.join();
	}

	@Test
	public void testHangReportSuppressedOnLongRunningFuture() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		getFactory().run(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(20))),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		Assert.assertFalse(hangReported.get());
	}

	@Test
	public void testHangReportSuppressedOnWaitingLongRunningFuture() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		getFactory().run(() -> {
			JoinableFuture<Void> task = getFactory().runAsync(
				() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(20))),
				EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

			return Async.awaitAsync(task);
		});

		Assert.assertFalse(hangReported.get());
	}

	@Test
	public void testHangReportSuppressedOnWaitingLongRunningFuture2() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(1));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		JoinableFuture<Void> task = getFactory().runAsync(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(30))),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		getFactory().run(() -> Async.awaitAsync(task));

		Assert.assertFalse(hangReported.get());
	}

	@Test
	public void testHangReportSuppressedOnJoiningLongRunningFuture() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		JoinableFuture<Void> task = getFactory().runAsync(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(30))),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		task.join();

		Assert.assertFalse(hangReported.get());
	}

	@Test
	public void testHangReportNotSuppressedOnUnrelatedLongRunningFuture() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		JoinableFuture<Void> task = getFactory().runAsync(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(40))),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		getFactory().run(() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(20))));

		Assert.assertTrue(hangReported.get());
		task.join();
	}

	@Test
	public void testHangReportNotSuppressedOnLongRunningFutureNoLongerJoined() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		JoinableFuture<Void> task = getFactory().runAsync(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(40))),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		JoinableFutureCollection taskCollection = new JoinableFutureCollection(getFactory().getContext());
		taskCollection.add(task);

		getFactory().run(() -> {
			return Async.awaitAsync(
				Async.usingAsync(
					taskCollection.join(),
					tempJoin -> Async.awaitAsync(Async.yieldAsync())),
				() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(20))));
		});

		Assert.assertTrue(hangReported.get());
		task.join();
	}

	@Test
	public void testHangReportNotSuppressedOnLongRunningFutureJoinCancelled() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		JoinableFuture<Void> task = getFactory().runAsync(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(40))),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		getFactory().run(() -> {
			CancellationTokenSource cancellationSource = new CancellationTokenSource();
			CompletableFuture<Void> joinTask = task.joinAsync(cancellationSource.getToken());
			cancellationSource.cancel();
			return Async.awaitAsync(
				TplExtensions.noThrowAwaitable(joinTask),
				() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(20))));
		});

		Assert.assertTrue(hangReported.get());
		task.join();
	}

	@Test
	public void testHangReportNotSuppressedOnLongRunningFutureCompleted() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);

		JoinableFuture<Void> task = getFactory().runAsync(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(30))),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		task.join();
		Assert.assertFalse(hangReported.get());

		JoinableFutureCollection taskCollection = new JoinableFutureCollection(getFactory().getContext());
		taskCollection.add(task);

		getFactory().run(() -> Async.usingAsync(
			taskCollection.join(),
			tempJoin -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(30)))));

		Assert.assertTrue(hangReported.get());
	}

	@Test
	public void testHangReportNotSuppressedOnLongRunningFutureCancelled() {
		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));
		AtomicBoolean hangReported = new AtomicBoolean(false);
		getContext().onReportHang = (hangDuration, iterations, id) -> hangReported.set(true);
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

		JoinableFuture<?> task = getFactory().runAsync(
			() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(40), cancellationTokenSource.getToken())),
			EnumSet.of(JoinableFutureCreationOption.LONG_RUNNING));

		JoinableFutureCollection taskCollection = new JoinableFutureCollection(getFactory().getContext());
		taskCollection.add(task);

		getFactory().run(() -> Async.usingAsync(
			taskCollection.join(),
			tempJoin -> {
				cancellationTokenSource.cancel();
				return Async.awaitAsync(
					TplExtensions.noThrowAwaitable(task.joinAsync()),
					() -> Async.awaitAsync(Async.delayAsync(Duration.ofMillis(40))));
			}));

		Assert.assertTrue(hangReported.get());
	}

	@Test
	public void testGetHangReportSimple() throws Exception {
		HangReportContributor contributor = getContext();
		HangReportContribution report = contributor.getHangReport();
		Assert.assertNotNull(report);
		Assert.assertEquals("application/xml", report.getContentType());
		Assert.assertNotNull(report.getContentName());
		System.out.println(report.getContent());

		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
		documentBuilderFactory.setNamespaceAware(true);
		DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
		Document dgml = builder.parse(new ByteArrayInputStream(report.getContent().getBytes(Charset.forName("UTF-8"))));

		Assert.assertEquals("DirectedGraph", dgml.getDocumentElement().getLocalName());
		Assert.assertEquals("http://schemas.microsoft.com/vs/2009/dgml", dgml.getDocumentElement().getNamespaceURI());
	}

//        [StaFact]
//        public void GetHangReportProducesDgmlWithNamedJoinableCollections()
//        {
//            const string jtcName = "My Collection";
//
//            this.joinableCollection.DisplayName = jtcName;
//            this.Factory.RunAsync(delegate
//            {
//                IHangReportContributor contributor = this.Context;
//                var report = contributor.GetHangReport();
//                this.Logger.WriteLine(report.Content);
//                var dgml = XDocument.Parse(report.Content);
//                var collectionLabels = from node in dgml.Root.Element(XName.Get("Nodes", DgmlNamespace)).Elements()
//                                       where node.Attribute(XName.Get("Category"))?.Value == "Collection"
//                                       select node.Attribute(XName.Get("Label"))?.Value;
//                Assert.True(collectionLabels.Any(label => label == jtcName));
//                return TplExtensions.CompletedTask;
//            });
//        }
//
//        [StaFact]
//        public void GetHangReportProducesDgmlWithMethodNameRequestingMainThread()
//        {
//            var mainThreadRequested = new ManualResetEventSlim();
//            Task.Run(delegate
//            {
//                var awaiter = this.Factory.SwitchToMainThreadAsync().GetAwaiter();
//                awaiter.OnCompleted(delegate { /* this anonymous delegate is expected to include the name of its containing method */ });
//                mainThreadRequested.Set();
//            });
//            mainThreadRequested.Wait();
//            IHangReportContributor contributor = this.Context;
//            var report = contributor.GetHangReport();
//            this.Logger.WriteLine(report.Content);
//            var dgml = XDocument.Parse(report.Content);
//            var collectionLabels = from node in dgml.Root.Element(XName.Get("Nodes", DgmlNamespace)).Elements()
//                                   where node.Attribute(XName.Get("Category"))?.Value == "Task"
//                                   select node.Attribute(XName.Get("Label"))?.Value;
//            Assert.True(collectionLabels.Any(label => label.Contains(nameof(this.GetHangReportProducesDgmlWithMethodNameRequestingMainThread))));
//        }
//
//        [StaFact(Skip = "Sadly, it seems JoinableTaskFactory.Post can't effectively override the labeled delegate because of another wrapper generated by the compiler.")]
//        public void GetHangReportProducesDgmlWithMethodNameYieldingOnMainThread()
//        {
//            this.ExecuteOnDispatcher(async delegate
//            {
//                var messagePosted = new AsyncManualResetEvent();
//                var nowait = Task.Run(async delegate
//                {
//                    await this.Factory.SwitchToMainThreadAsync();
//                    var nowait2 = this.YieldingMethodAsync();
//                    messagePosted.Set();
//                });
//                await messagePosted.WaitAsync();
//                IHangReportContributor contributor = this.Context;
//                var report = contributor.GetHangReport();
//                this.Logger.WriteLine(report.Content);
//                var dgml = XDocument.Parse(report.Content);
//                var collectionLabels = from node in dgml.Root.Element(XName.Get("Nodes", DgmlNamespace)).Elements()
//                                       where node.Attribute(XName.Get("Category"))?.Value == "Task"
//                                       select node.Attribute(XName.Get("Label"))?.Value;
//                Assert.True(collectionLabels.Any(label => label.Contains(nameof(this.YieldingMethodAsync))));
//            });
//        }

	@Test
	@Ignore("https://github.com/Microsoft/vs-threading/issues/82")
	public void testGetHangReportWithActualHang() {
		CancellationTokenSource endTestCancellationSource = new CancellationTokenSource();
		getContext().onReportHang = (hangDuration, iterations, id) -> {
			HangReportContributor contributor = getContext();
			HangReportContribution report = contributor.getHangReport();
			Assert.assertNotNull(report);
			System.out.println(report.getContent());
			endTestCancellationSource.cancel();
			getContext().onReportHang = null;
		};

		getFactory().setHangDetectionTimeout(Duration.ofMillis(10));

		thrown.expect(CancellationException.class);
		getFactory().run(() -> Async.usingAsync(
			getContext().suppressRelevance(),
			ignored -> Futures.supply(() -> Async.awaitAsync(
				getFactory().runAsync(
					() -> Async.awaitAsync(getFactory().switchToMainThreadAsync(endTestCancellationSource.getToken())))))));
	}

	@Test
	public void testIsMainThreadBlockedFalseWithNoFuture() {
		Assert.assertFalse(getContext().isMainThreadBlocked());
	}

	@Test
	public void testIsMainThreadBlockedFalseWhenAsync() {
		JoinableFuture<Void> joinable = getFactory().runAsync(() -> {
			Assert.assertFalse(getContext().isMainThreadBlocked());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertFalse(getContext().isMainThreadBlocked());
					testFrame.setContinue(false);
					return Futures.completedNull();
				});
		});

		pushFrame();

		// rethrow exceptions
		joinable.join();
	}

	@Test
	public void testIsMainThreadBlockedTrueWhenAsyncBecomesBlocking() {
		JoinableFuture<Void> joinable = getFactory().runAsync(() -> {
			Assert.assertFalse(getContext().isMainThreadBlocked());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					// we're now running on top of Join()
					Assert.assertTrue(getContext().isMainThreadBlocked());
					return Async.awaitAsync(
						AwaitExtensions.switchTo(ForkJoinPool.commonPool(), true),
						() -> {
							// although we're on background thread, we're blocking main thread.
							Assert.assertTrue(getContext().isMainThreadBlocked());

							return Async.awaitAsync(getFactory().runAsync(() -> {
								Assert.assertTrue(getContext().isMainThreadBlocked());
								return Async.awaitAsync(
									Async.yieldAsync(),
									() -> {
										Assert.assertTrue(getContext().isMainThreadBlocked());
										return Async.awaitAsync(
											getFactory().switchToMainThreadAsync(),
											() -> {
												Assert.assertTrue(getContext().isMainThreadBlocked());
												return Futures.completedNull();
											});
									});
							}));
						});
				});
		});

		joinable.join();
	}

	@Test
	public void testIsMainThreadBlockedTrueWhenAsyncBecomesBlockingWithNestedFuture() {
		JoinableFuture<Void> joinable = getFactory().runAsync(() -> {
			Assert.assertFalse(getContext().isMainThreadBlocked());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertFalse(getContext().isMainThreadBlocked());
					return Async.awaitAsync(
						AwaitExtensions.switchTo(ForkJoinPool.commonPool(), true),
						() -> {
							Assert.assertFalse(getContext().isMainThreadBlocked());

							return Async.awaitAsync(getFactory().runAsync(() -> {
								Assert.assertFalse(getContext().isMainThreadBlocked());

								return Async.awaitAsync(
									// Now release the message pump so we hit the Join() call
									getFactory().switchToMainThreadAsync(),
									() -> {
										testFrame.setContinue(false);
										return Async.awaitAsync(
											Async.yieldAsync(),
											() -> {
												// From now on, we're blocking.
												Assert.assertTrue(getContext().isMainThreadBlocked());
												return Async.awaitAsync(
													AwaitExtensions.switchTo(ForkJoinPool.commonPool(), true),
													() -> {
														Assert.assertTrue(getContext().isMainThreadBlocked());
														return Futures.completedNull();
													});
											});
									});
							}));
						});
				});
		});

		// for duration of this, it appears to be non-blocking.
		pushFrame();
		joinable.join();
	}

	@Test
	public void testIsMainThreadBlockedTrueWhenOriginallySync() {
		getFactory().run(() -> {
			Assert.assertTrue(getContext().isMainThreadBlocked());
			return Async.awaitAsync(
				Async.yieldAsync(),
				() -> {
					Assert.assertTrue(getContext().isMainThreadBlocked());
					return Async.awaitAsync(
						AwaitExtensions.switchTo(ForkJoinPool.commonPool(), true),
						() -> {
							Assert.assertTrue(getContext().isMainThreadBlocked());

							return Async.awaitAsync(getFactory().runAsync(() -> {
								Assert.assertTrue(getContext().isMainThreadBlocked());
								return Async.awaitAsync(
									Async.yieldAsync(),
									() -> {
										Assert.assertTrue(getContext().isMainThreadBlocked());
										return Async.awaitAsync(
											getFactory().switchToMainThreadAsync(),
											() -> {
												Assert.assertTrue(getContext().isMainThreadBlocked());
												return Futures.completedNull();
											});
									});
							}));
						});
				});
		});
	}

	@Test
	public void testIsMainThreadBlockedFalseWhenSyncBlockingOtherThread() {
		CompletableFuture<Void> task = Futures.runAsync(() -> {
			getFactory().run(() -> {
				Assert.assertFalse(getContext().isMainThreadBlocked());
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> {
						Assert.assertFalse(getContext().isMainThreadBlocked());
						return Futures.completedNull();
					});
			});
		});

		task.join();
	}

	@Test
	public void testIsMainThreadBlockedTrueWhenAsyncOnOtherThreadBecomesSyncOnMainThread() {
		AsyncManualResetEvent nonBlockingStateObserved = new AsyncManualResetEvent();
		AsyncManualResetEvent nowBlocking = new AsyncManualResetEvent();
		StrongBox<JoinableFuture<Void>> joinableTask = new StrongBox<>();
		Futures.runAsync(() -> {
			joinableTask.value = getFactory().runAsync(() -> {
				Assert.assertFalse(getContext().isMainThreadBlocked());
				nonBlockingStateObserved.set();
				return Async.awaitAsync(
					Async.yieldAsync(),
					() -> Async.awaitAsync(
						nowBlocking,
						() -> {
							Assert.assertTrue(getContext().isMainThreadBlocked());
							return Futures.completedNull();
						}));
			});
		}).join();

		getFactory().run(() -> Async.awaitAsync(
			nonBlockingStateObserved,
			() -> {
				TplExtensions.forget(joinableTask.value.joinAsync());
				nowBlocking.set();
				return Futures.completedNull();
			}));
	}

//        [StaFact]
//        public void RevertRelevanceDefaultValue()
//        {
//            var revert = default(JoinableTaskContext.RevertRelevance);
//            revert.Dispose();
//        }

	@Test
	public void testDisposable() {
		Disposable disposable = getContext();
		disposable.close();
	}

	@NotNull
	@Override
	protected JoinableFutureContext createJoinableFutureContext() {
		return new JoinableFutureContextDerived();
	}

	/**
	 * A method that does nothing but yield once.
	 */
	@NotNull
	private CompletableFuture<Void> yieldingMethodAsync() {
		return Async.awaitAsync(Async.yieldAsync());
	}

	private static class JoinableFutureContextDerived extends JoinableFutureContext {

		TriConsumer<Duration, Integer, UUID> onReportHang;

		@Override
		public JoinableFutureFactory createFactory(JoinableFutureCollection collection) {
			return new JoinableFutureFactoryDerived(collection);
		}

		@Override
		protected JoinableFutureFactory createDefaultFactory() {
			return new JoinableFutureFactoryDerived(this);
		}

		@Override
		protected void onHangDetected(Duration hangDuration, int notificationCount, UUID hangId) {
			if (onReportHang != null) {
				onReportHang.accept(hangDuration, notificationCount, hangId);
			}
		}
	}

	private static class JoinableFutureFactoryDerived extends JoinableFutureFactory {

		JoinableFutureFactoryDerived(JoinableFutureContext context) {
			super(context);
		}

		JoinableFutureFactoryDerived(JoinableFutureCollection collection) {
			super(collection);
		}
	}

	private interface TriConsumer<T, U, V> {

		/**
		 * Performs this operation on the given arguments.
		 *
		 * @param t the first input argument
		 * @param u the second input argument
		 * @param v the third input argument
		 */
		void accept(T t, U u, V v);
	}
}
