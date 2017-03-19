// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.isA;

public class ThreadPropertiesTest extends TestBase {
	@Test
	public void testGetExecutionContext_CurrentThread() {
		Assert.assertNull(ThreadProperties.getExecutionContext(Thread.currentThread()));
	}

	@Test
	public void testGetExecutionContext_DifferentThread() {
		Thread currentThread = Thread.currentThread();
		CompletableFuture<Void> asyncTest = Futures.runAsync(() -> {
			ThreadProperties.getExecutionContext(currentThread);
		});

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(AssertionError.class));
		asyncTest.join();
	}

	@Test
	public void testSetExecutionContext_CurrentThread() {
		Assert.assertNotNull(ExecutionContext.DEFAULT);

		ThreadProperties.setExecutionContext(Thread.currentThread(), ExecutionContext.DEFAULT);
		Assert.assertSame(ExecutionContext.DEFAULT, ThreadProperties.getExecutionContext(Thread.currentThread()));

		ThreadProperties.setExecutionContext(Thread.currentThread(), null);
		Assert.assertNull(ThreadProperties.getExecutionContext(Thread.currentThread()));
	}

	@Test
	public void testSetExecutionContext_DifferentThread() {
		Thread currentThread = Thread.currentThread();
		CompletableFuture<Void> asyncTest = Futures.runAsync(() -> {
			ThreadProperties.setExecutionContext(currentThread, null);
		});

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(AssertionError.class));
		asyncTest.join();
	}

	@Test
	public void testGetSynchronizationContext_CurrentThread() {
		Assert.assertNull(ThreadProperties.getSynchronizationContext(Thread.currentThread()));
	}

	@Test
	public void testGetSynchronizationContext_DifferentThread() {
		Thread currentThread = Thread.currentThread();
		CompletableFuture<Void> asyncTest = Futures.runAsync(() -> {
			ThreadProperties.getSynchronizationContext(currentThread);
		});

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(AssertionError.class));
		asyncTest.join();
	}

	@Test
	public void testSetSynchronizationContext_CurrentThread() {
		SynchronizationContext context = new SynchronizationContext();

		ThreadProperties.setSynchronizationContext(Thread.currentThread(), context);
		Assert.assertSame(context, ThreadProperties.getSynchronizationContext(Thread.currentThread()));

		ThreadProperties.setSynchronizationContext(Thread.currentThread(), null);
		Assert.assertNull(ThreadProperties.getSynchronizationContext(Thread.currentThread()));
	}

	@Test
	public void testSetSynchronizationContext_DifferentThread() {
		Thread currentThread = Thread.currentThread();
		CompletableFuture<Void> asyncTest = Futures.runAsync(() -> {
			ThreadProperties.setSynchronizationContext(currentThread, null);
		});

		thrown.expect(CompletionException.class);
		thrown.expectCause(isA(AssertionError.class));
		asyncTest.join();
	}
}
