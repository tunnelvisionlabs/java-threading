// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFutureContext.HangDetails;
import com.tunnelvisionlabs.util.concurrent.JoinableFutureContext.RevertRelevance;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.CoreMatchers.equalTo;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureContextNodeTest extends JoinableFutureTestBase {
	private JoinableFutureContextNode defaultNode;

	private DerivedNode derivedNode;

	@Before
	public void setupNodes() {
		defaultNode = new JoinableFutureContextNode(context);
		derivedNode = new DerivedNode(context);
	}

	@Test
	public void testCreateCollection() {
		JoinableFutureCollection collection = defaultNode.createCollection();
		Assert.assertNotNull(collection);

		collection = derivedNode.createCollection();
		Assert.assertNotNull(collection);
	}

	@Test
	public void testCreateFactory() {
		JoinableFutureFactory factory = defaultNode.createFactory(joinableCollection);
		Assert.assertNotNull(factory);
		Assert.assertThat(factory.getClass(), equalTo(JoinableFutureFactory.class));

		factory = derivedNode.createFactory(joinableCollection);
		Assert.assertNotNull(factory);
		Assert.assertThat(factory.getClass(), equalTo(DerivedFactory.class));
	}

	@Test
	public void testFactory() {
		Assert.assertThat(defaultNode.getFactory().getClass(), equalTo(JoinableFutureFactory.class));
		Assert.assertThat(derivedNode.getFactory().getClass(), equalTo(DerivedFactory.class));
	}

	@Test
	public void testMainThread() {
//#if DESKTOP
		Assert.assertSame(context.getMainThread(), defaultNode.getMainThread());
		Assert.assertSame(context.getMainThread(), derivedNode.getMainThread());
//#endif
		Assert.assertTrue(context.isOnMainThread());
		Assert.assertTrue(derivedNode.isOnMainThread());
	}

	@Test
	public void testIsMainThreadBlocked() {
		Assert.assertFalse(defaultNode.isMainThreadBlocked());
		Assert.assertFalse(derivedNode.isMainThreadBlocked());
	}

	@Test
	public void SuppressRelevance() {
		try (RevertRelevance revert = defaultNode.suppressRelevance()) {
		}

		try (RevertRelevance revert = derivedNode.suppressRelevance()) {
		}
	}

	@Test
	@Category(FailsInCloudTest.class)
	public void testOnHangDetected_Registration() {
		DerivedFactory factory = (DerivedFactory)this.derivedNode.getFactory();
		factory.setHangDetectionTimeout(Duration.ofMillis(1));
		factory.run(() -> Async.awaitAsync(Async.delayAsync(2, TimeUnit.MILLISECONDS)));

		Assert.assertFalse("we didn't register, so we shouldn't get notifications", derivedNode.getHangDetected().isSet());
		Assert.assertFalse(derivedNode.getFalseHangReportDetected().isSet());

		try (Disposable disposable = derivedNode.registerOnHangDetected()) {
			factory.run(() -> {
				CompletableFuture<Void> timeout = Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT);
				return Async.awaitAsync(
					Async.whenAny(timeout, derivedNode.getHangDetected().waitAsync()),
					result -> {
						Assert.assertNotSame("Timed out waiting for hang detection.", timeout, result);
						return Futures.completedNull();
					});
			});
			Assert.assertTrue(derivedNode.getHangDetected().isSet());
			Assert.assertTrue(derivedNode.getFalseHangReportDetected().isSet());
			Assert.assertEquals(derivedNode.getHangReportCount(), derivedNode.getHangDetails().getNotificationCount());
			Assert.assertEquals(1, derivedNode.getFalseHangReportCount());

			// reset for the next verification
			derivedNode.getHangDetected().reset();
			derivedNode.getFalseHangReportDetected().reset();
		}

		factory.run(() -> Async.awaitAsync(Async.delayAsync(2, TimeUnit.MILLISECONDS)));
		Assert.assertFalse("registration should have been canceled.", derivedNode.getHangDetected().isSet());
		Assert.assertFalse(derivedNode.getFalseHangReportDetected().isSet());
	}

	@Test
	@Category(FailsInCloudTest.class)
	public void testOnFalseHangReportDetected_OnlyOnce() {
		DerivedFactory factory = (DerivedFactory)derivedNode.getFactory();
		factory.setHangDetectionTimeout(Duration.ofMillis(1));
		derivedNode.registerOnHangDetected();

		JoinableFuture<?> dectionTask = factory.runAsync(() -> Async.awaitAsync(
			ForkJoinPool.commonPool(),
			() -> Async.awaitAsync(
				Async.forAsync(
					() -> 0,
					i -> i < 2,
					i -> i + 1,
					i -> Async.awaitAsync(
						derivedNode.getHangDetected().waitAsync(),
						() -> {
							derivedNode.getHangDetected().reset();
							return Futures.completedNull();
						})),
				() -> Async.awaitAsync(derivedNode.getHangDetected().waitAsync()))));

		factory.run(() -> {
			CompletableFuture<Void> timeout = Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT);
			return Async.awaitAsync(
				Async.whenAny(timeout, dectionTask.getFuture()),
				result -> {
					Assert.assertNotSame("Timed out waiting for hang detection.", timeout, result);
					return Async.awaitAsync(dectionTask);
				});
		});

		Assert.assertTrue(derivedNode.getHangDetected().isSet());
		Assert.assertTrue(derivedNode.getFalseHangReportDetected().isSet());
		Assert.assertEquals(derivedNode.getHangDetails().getHangId(), derivedNode.getFalseHangReportId());
		Assert.assertTrue(derivedNode.getFalseHangReportDuration().compareTo(derivedNode.getHangDetails().getHangDuration()) >= 0);
		Assert.assertTrue(derivedNode.getHangReportCount() >= 3);
		Assert.assertEquals(derivedNode.getHangReportCount(), this.derivedNode.getHangDetails().getNotificationCount());
		Assert.assertEquals(1, derivedNode.getFalseHangReportCount());
	}

	@Test
	@Category(FailsInCloudTest.class)
	@Ignore("Entry point isn't handled correctly")
	public void testOnHangDetected_Run_OnMainThread() {
		DerivedFactory factory = (DerivedFactory)derivedNode.getFactory();
		factory.setHangDetectionTimeout(Duration.ofMillis(1));
		derivedNode.registerOnHangDetected();

		factory.run(() -> {
			CompletableFuture<Void> timeout = Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT);
			return Async.awaitAsync(
				Async.whenAny(timeout, this.derivedNode.getHangDetected().waitAsync()),
				result -> {
					Assert.assertNotSame("Timed out waiting for hang detection.", timeout, result);
					return Futures.completedNull();
				});
		});
		Assert.assertTrue(this.derivedNode.getHangDetected().isSet());
		Assert.assertNotNull(this.derivedNode.getHangDetails());
		Assert.assertNotNull(this.derivedNode.getHangDetails().getEntryMethod());
		Assert.assertSame(this.getClass(), this.derivedNode.getHangDetails().getEntryMethod().getDeclaringClass());
		Assert.assertTrue(this.derivedNode.getHangDetails().getEntryMethod().getName().contains("OnHangDetected_Run_OnMainThread"));

		Assert.assertTrue(this.derivedNode.getFalseHangReportDetected().isSet());
		Assert.assertNotEquals(new UUID(0, 0), this.derivedNode.getFalseHangReportId());
		Assert.assertEquals(this.derivedNode.getHangDetails().getHangId(), this.derivedNode.getFalseHangReportId());
		Assert.assertTrue(this.derivedNode.getFalseHangReportDuration().compareTo(this.derivedNode.getHangDetails().getHangDuration()) >= 0);
	}

	@Test
	@Category(FailsInCloudTest.class)
	@Ignore("Entry point isn't handled correctly")
	public void testOnHangDetected_Run_OffMainThread() {
		Async.runAsync(() -> {
			// Now that we're off the main thread, just call the other test.
			testOnHangDetected_Run_OnMainThread();
		}).join();
	}

	@Test
	@Ignore("Entry point isn't handled correctly")
	public void testOnHangDetected_RunAsync_OnMainThread_BlamedMethodIsEntrypointNotBlockingMethod() {
		DerivedFactory factory = (DerivedFactory)derivedNode.getFactory();
		factory.setHangDetectionTimeout(Duration.ofMillis(1));
		derivedNode.registerOnHangDetected();

		JoinableFuture<Void> jt = factory.runAsync(() -> {
			CompletableFuture<Void> timeout = Async.delayAsync(ASYNC_DELAY, ASYNC_DELAY_UNIT);
			return Async.awaitAsync(
				Async.whenAny(timeout, derivedNode.getHangDetected().waitAsync()),
				result -> {
					Assert.assertNotSame("Timed out waiting for hang detection.", timeout, result);
					return Futures.completedNull();
				});
		});
		onHangDetected_BlockingMethodHelper(jt);
		Assert.assertTrue(derivedNode.getHangDetected().isSet());
		Assert.assertNotNull(derivedNode.getHangDetails());
		Assert.assertNotNull(derivedNode.getHangDetails().getEntryMethod());

		// Verify that the original method that spawned the JoinableTask is the one identified as the entrypoint method.
		Assert.assertSame(getClass(), derivedNode.getHangDetails().getEntryMethod().getDeclaringClass());
		Assert.assertTrue(derivedNode.getHangDetails().getEntryMethod().getName().contains("testOnHangDetected_RunAsync_OnMainThread_BlamedMethodIsEntrypointNotBlockingMethod"));
	}

	@Test
	@Ignore("Entry point isn't handled correctly")
	public void testOnHangDetected_RunAsync_OffMainThread_BlamedMethodIsEntrypointNotBlockingMethod() {
		Async.runAsync(() -> {
			// Now that we're off the main thread, just call the other test.
			testOnHangDetected_RunAsync_OnMainThread_BlamedMethodIsEntrypointNotBlockingMethod();
		}).join();
	}

	/**
	 * A helper method that just blocks on the completion of a {@link JoinableFuture}.
	 *
	 * <p>This method is explicitly defined rather than using an anonymous method because we do NOT want the calling
	 * method's name embedded into this method's name by the compiler so that we can verify based on method name.</p>
	 */
	private static void onHangDetected_BlockingMethodHelper(@NotNull JoinableFuture<?> future) {
		future.join();
	}

	private static class DerivedNode extends JoinableFutureContextNode {

		private final AsyncManualResetEvent hangDetected = new AsyncManualResetEvent();
		private final AsyncManualResetEvent falseHangReportDetected = new AsyncManualResetEvent();

		private HangDetails hangDetails;
		private UUID falseHangReportId;
		private Duration falseHangReportDuration;
		private int hangReportCount;
		private int falseHangReportCount;

		DerivedNode(JoinableFutureContext context) {
			super(context);
		}

		@NotNull
		final AsyncManualResetEvent getHangDetected() {
			return hangDetected;
		}

		@NotNull
		final AsyncManualResetEvent getFalseHangReportDetected() {
			return falseHangReportDetected;
		}

		final HangDetails getHangDetails() {
			return hangDetails;
		}

		final UUID getFalseHangReportId() {
			return falseHangReportId;
		}

		final Duration getFalseHangReportDuration() {
			return falseHangReportDuration;
		}

		final int getHangReportCount() {
			return hangReportCount;
		}

		final int getFalseHangReportCount() {
			return falseHangReportCount;
		}

		@Override
		public JoinableFutureFactory createFactory(JoinableFutureCollection collection) {
			return new DerivedFactory(collection);
		}

//		@Override
//		final Disposable registerOnHangDetected() {
//			return super.registerOnHangDetected();
//		}

		@Override
		protected JoinableFutureFactory createDefaultFactory() {
			return new DerivedFactory(getContext());
		}

		@Override
		protected void onHangDetected(HangDetails details) {
			this.hangDetails = details;
			this.getHangDetected().set();
			this.hangReportCount++;
			super.onHangDetected(details);
		}

		@Override
		protected void onFalseHangDetected(Duration hangDuration, UUID hangId) {
			this.getFalseHangReportDetected().set();
			this.falseHangReportId = hangId;
			this.falseHangReportDuration = hangDuration;
			this.falseHangReportCount++;
			super.onFalseHangDetected(hangDuration, hangId);
		}
	}

	private static class DerivedFactory extends JoinableFutureFactory {

		DerivedFactory(JoinableFutureContext context) {
			super(context);
		}

		DerivedFactory(JoinableFutureCollection collection) {
			super(collection);
		}

//            internal new TimeSpan HangDetectionTimeout
//            {
//                get { return base.HangDetectionTimeout; }
//                set { base.HangDetectionTimeout = value; }
//            }
	}

}
