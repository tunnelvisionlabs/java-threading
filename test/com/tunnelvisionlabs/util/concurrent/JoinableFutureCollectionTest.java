// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.concurrent.JoinableFutureCollection.JoinRelease;
import java.util.concurrent.CompletableFuture;
import org.junit.Assert;
import org.junit.Test;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class JoinableFutureCollectionTest extends JoinableFutureTestBase {
	@NotNull
	protected final JoinableFutureFactory getJoinableFactory() {
		return this.asyncPump;
	}

	@Test
	public void testDisplayName() {
		JoinableFutureCollection jtc = new JoinableFutureCollection(context);
		Assert.assertNull(jtc.getDisplayName());
		jtc.setDisplayName("");
		Assert.assertEquals("", jtc.getDisplayName());
		jtc.setDisplayName(null);
		Assert.assertNull(jtc.getDisplayName());
		jtc.setDisplayName("My Name");
		Assert.assertEquals("My Name", jtc.getDisplayName());
	}

	@Test
	public void testJoinUntilEmptyAlreadyCompleted() {
		CompletableFuture<Void> awaiter = this.joinableCollection.joinUntilEmptyAsync();
		Assert.assertTrue(awaiter.isDone());
	}

	@Test
	public void testJoinUntilEmptyWithOne() {
		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		JoinableFuture<Void> joinable = getJoinableFactory().runAsync(() -> Async.awaitAsync(evt.waitAsync()));

		CompletableFuture<Void> waiter = joinableCollection.joinUntilEmptyAsync();
		Assert.assertFalse(waiter.isDone());
		Futures.runAsync(() -> {
			evt.set();
			return Async.awaitAsync(
				waiter,
				() -> {
					this.testFrame.setContinue(false);
					return Futures.completedNull();
				});
		});

		pushFrame();
	}

	@Test
	public void testJoinUntilEmptyUsesConfigureAwaitFalse() throws Exception {
		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		JoinableFuture<Void> joinable = getJoinableFactory().runAsync(() -> Async.awaitAsync(evt.waitAsync(), false));

		CompletableFuture<Void> waiter = joinableCollection.joinUntilEmptyAsync();
		Assert.assertFalse(waiter.isDone());
		evt.set();
		waiter.get(UNEXPECTED_TIMEOUT, UNEXPECTED_TIMEOUT_UNIT);
	}

	@Test
	public void testEmptyThenMore() {
		CompletableFuture<Void> awaiter = joinableCollection.joinUntilEmptyAsync();
		Assert.assertTrue(awaiter.isDone());

		AsyncManualResetEvent evt = new AsyncManualResetEvent();
		JoinableFuture<Void> joinable = getJoinableFactory().runAsync(() -> Async.awaitAsync(evt));

		CompletableFuture<Void> waiter = joinableCollection.joinUntilEmptyAsync();
		Assert.assertFalse(waiter.isDone());
		Futures.runAsync(() -> {
			evt.set();
			return Async.awaitAsync(
				waiter,
				() -> {
					testFrame.setContinue(false);
					return Futures.completedNull();
				});
		});
		pushFrame();
	}

	@Test
	public void testJoinUntilEmptyAsyncJoinsCollection() {
		JoinableFuture<Void> joinable = getJoinableFactory().runAsync(() -> Async.awaitAsync(Async.yieldAsync()));

		context.getFactory().run(() -> Async.awaitAsync(joinableCollection.joinUntilEmptyAsync()));
	}

	@Test
	public void testAddTwiceRemoveOnceRemovesWhenNotRefCounting() {
		AsyncManualResetEvent finishTaskEvent = new AsyncManualResetEvent();
		JoinableFuture<Void> task = getJoinableFactory().runAsync(() -> Async.awaitAsync(finishTaskEvent));

		JoinableFutureCollection collection = new JoinableFutureCollection(context, /*refCountAddedJobs:*/ false);
		collection.add(task);
		Assert.assertTrue(collection.contains(task));
		collection.add(task);
		Assert.assertTrue(collection.contains(task));
		collection.remove(task);
		Assert.assertFalse(collection.contains(task));

		finishTaskEvent.set();
	}

	@Test
	public void testAddTwiceRemoveTwiceRemovesWhenRefCounting() {
		AsyncManualResetEvent finishTaskEvent = new AsyncManualResetEvent();
		JoinableFuture<Void> task = getJoinableFactory().runAsync(() -> Async.awaitAsync(finishTaskEvent));

		JoinableFutureCollection collection = new JoinableFutureCollection(context, /*refCountAddedJobs:*/ true);
		collection.add(task);
		Assert.assertTrue(collection.contains(task));
		collection.add(task);
		Assert.assertTrue(collection.contains(task));
		collection.remove(task);
		Assert.assertTrue(collection.contains(task));
		collection.remove(task);
		Assert.assertFalse(collection.contains(task));

		finishTaskEvent.set();
	}

	@Test
	public void testAddTwiceRemoveOnceRemovesCompletedFutureWhenRefCounting() {
		AsyncManualResetEvent finishTaskEvent = new AsyncManualResetEvent();
		JoinableFuture<Void> task = getJoinableFactory().runAsync(() -> Async.awaitAsync(finishTaskEvent));

		JoinableFutureCollection collection = new JoinableFutureCollection(context, /*refCountAddedJobs:*/ true);
		collection.add(task);
		Assert.assertTrue(collection.contains(task));
		collection.add(task);
		Assert.assertTrue(collection.contains(task));

		finishTaskEvent.set();
		task.join();

		// technically the JoinableFuture is probably gone from the collection by now anyway.
		collection.remove(task);
		Assert.assertFalse(collection.contains(task));
	}

	@Test
	public void testJoinClosedTwice() {
		getJoinableFactory().run(() -> {
			JoinRelease releaser = joinableCollection.join();
			releaser.close();
			releaser.close();

			return Futures.completedNull();
		});
	}
}
