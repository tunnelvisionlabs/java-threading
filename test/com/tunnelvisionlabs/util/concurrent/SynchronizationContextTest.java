package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import org.junit.Assert;
import org.junit.Test;

public class SynchronizationContextTest extends TestBase {

	@Test
	public void testSynchronizationContextNoAppliedToThreadPool_Runnable() {
		SynchronizationContext context = new SynchronizationContext() {
			// Sometimes SynchronizationContext.class is used to trigger special behavior, so we force it to extend the
			// base class.
		};

		Thread currentThread = Thread.currentThread();
		SynchronizationContext.setSynchronizationContext(context);
		CompletableFuture<Void> asyncTest = Async.runAsync(() -> {
			Assert.assertNotSame(currentThread, Thread.currentThread());
			Assert.assertNotSame(context, SynchronizationContext.getCurrent());
		});

		asyncTest.join();
	}

	@Test
	public void testSynchronizationContextNoAppliedToThreadPool_Supplier() {
		SynchronizationContext context = new SynchronizationContext() {
			// Sometimes SynchronizationContext.class is used to trigger special behavior, so we force it to extend the
			// base class.
		};

		Thread currentThread = Thread.currentThread();
		SynchronizationContext.setSynchronizationContext(context);
		CompletableFuture<Void> asyncTest = Async.runAsync(() -> {
			Assert.assertNotSame(currentThread, Thread.currentThread());
			Assert.assertNotSame(context, SynchronizationContext.getCurrent());
			return Futures.completedNull();
		});

		asyncTest.join();
	}

	@Test
	public void testSynchronizationContextNoAppliedToThreadPool_Function() {
		SynchronizationContext context = new SynchronizationContext() {
			// Sometimes SynchronizationContext.class is used to trigger special behavior, so we force it to extend the
			// base class.
		};

		Thread currentThread = Thread.currentThread();
		SynchronizationContext.setSynchronizationContext(context);
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			AwaitExtensions.switchTo(ForkJoinPool.commonPool()),
			ignored -> {
				Assert.assertNotSame(currentThread, Thread.currentThread());
				Assert.assertNotSame(context, SynchronizationContext.getCurrent());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

}
