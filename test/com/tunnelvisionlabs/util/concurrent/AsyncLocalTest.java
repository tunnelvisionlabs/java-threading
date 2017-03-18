// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Assert;
import org.junit.Test;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class AsyncLocalTest extends TestBase {
	private AsyncLocal<GenericParameterHelper> asyncLocal = new AsyncLocal<>();

	@Test
	public void testSetGetNoYield() {
		GenericParameterHelper value = new GenericParameterHelper();
		asyncLocal.setValue(value);
		Assert.assertSame(value, asyncLocal.getValue());
		asyncLocal.setValue(null);
		Assert.assertNull(asyncLocal.getValue());
	}

	@Test
	public void testSetGetWithYield() {
		GenericParameterHelper value = new GenericParameterHelper();
		asyncLocal.setValue(value);
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			Async.yieldAsync(),
			() -> {
				Assert.assertSame(value, asyncLocal.getValue());
				asyncLocal.setValue(null);
				Assert.assertNull(asyncLocal.getValue());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testSetGetWithDelay() {
		GenericParameterHelper value = new GenericParameterHelper();
		asyncLocal.setValue(value);
		CompletableFuture<Void> asyncTest = Async.delayAsync(Duration.ofMillis(10)).thenRun(
			() -> {
				Assert.assertSame(value, asyncLocal.getValue());
				asyncLocal.setValue(null);
				Assert.assertNull(asyncLocal.getValue());
			});

		asyncTest.join();
	}

	@Test
	public void testForkedContext() {
		GenericParameterHelper value = new GenericParameterHelper();
		asyncLocal.setValue(value);
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			CompletableFuture.allOf(
				Futures.runAsync(() -> {
					Assert.assertSame(value, asyncLocal.getValue());
					asyncLocal.setValue(null);
					Assert.assertNull(asyncLocal.getValue());
				}),
				Futures.runAsync(() -> {
					Assert.assertSame(value, asyncLocal.getValue());
					asyncLocal.setValue(null);
					Assert.assertNull(asyncLocal.getValue());
				})),
			() -> {
				Assert.assertSame(value, asyncLocal.getValue());
				asyncLocal.setValue(null);
				Assert.assertNull(asyncLocal.getValue());
				return Futures.completedNull();
			});

		asyncTest.join();
	}

	@Test
	public void testIndependentValuesBetweenContexts() {
		CompletableFuture<Void> asyncTest = Async.awaitAsync(
			independentValuesBetweenContextsHelper(GenericParameterHelper.class),
			() -> Async.awaitAsync(independentValuesBetweenContextsHelper(Object.class)));
		asyncTest.join();
	}

	@Test
	public void testSetNewValuesRepeatedly() {
		for (int i = 0; i < 10; i++) {
			GenericParameterHelper value = new GenericParameterHelper();
			asyncLocal.setValue(value);
			Assert.assertSame(value, asyncLocal.getValue());
		}

		asyncLocal.setValue(null);
		Assert.assertNull(asyncLocal.getValue());
	}

	@Test
	public void testSetSameValuesRepeatedly() {
		GenericParameterHelper value = new GenericParameterHelper();
		for (int i = 0; i < 10; i++) {
			asyncLocal.setValue(value);
			Assert.assertSame(value, asyncLocal.getValue());
		}

		asyncLocal.setValue(null);
		Assert.assertNull(asyncLocal.getValue());
	}

//        [Fact, Trait("GC", "true")]
//        public void SurvivesGC()
//        {
//            var value = new GenericParameterHelper(5);
//            this.asyncLocal.Value = value;
//            Assert.Same(value, this.asyncLocal.Value);
//
//            GC.Collect();
//            Assert.Same(value, this.asyncLocal.Value);
//
//            value = null;
//            GC.Collect();
//            Assert.Equal(5, this.asyncLocal.Value.Data);
//        }
//
//        [Fact]
//        public void NotDisruptedByTestContextWriteLine()
//        {
//            var value = new GenericParameterHelper();
//            this.asyncLocal.Value = value;
//
//            // TestContext.WriteLine causes the CallContext to be serialized.
//            // When a .testsettings file is applied to the test runner, the
//            // original contents of the CallContext are replaced with a
//            // serialize->deserialize clone, which can break the reference equality
//            // of the objects stored in the AsyncLocal class's private fields
//            // if it's not done properly.
//            this.Logger.WriteLine("Foobar");
//
//            Assert.NotNull(this.asyncLocal.Value);
//            Assert.Same(value, this.asyncLocal.Value);
//        }

	@Test
	public void testValuePersistsAcrossExecutionContextChanges() {
		AsyncLocal<Integer> jtLocal = new AsyncLocal<>();
		jtLocal.setValue(1);
		Supplier<CompletableFuture<Void>> asyncMethod = () -> {
			AtomicReference<CompletableFuture<Void>> result = new AtomicReference<>();
			ExecutionContext.wrap(() -> {
				try {
					SynchronizationContext.setSynchronizationContext(new SynchronizationContext());
					Assert.assertEquals(1, (int)jtLocal.getValue());
					jtLocal.setValue(3);
					Assert.assertEquals(3, (int)jtLocal.getValue());
					result.set(Async.awaitAsync(
						ForkJoinPool.commonPool(),
						() -> {
							Assert.assertEquals(3, (int)jtLocal.getValue());
							return Futures.completedNull();
						}));
				} catch (Throwable ex) {
					result.set(Futures.completedFailed(ex));
				}
			}).run();
			return result.get();
		};
		asyncMethod.get().join();

		Assert.assertEquals(1, (int)jtLocal.getValue());
	}

	@Test
	//[Trait("Performance", "true")]
	public void testAsyncLocalPerf() {
		GenericParameterHelper[] values = new GenericParameterHelper[50000];
		for (int i = 0; i < values.length; i++) {
			values[i] = new GenericParameterHelper(i + 1);
		}

		long creates = System.nanoTime();
		for (int i = 0; i < values.length; i++) {
			asyncLocal = new AsyncLocal<>();
		}

		creates = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - creates);

		long writes = System.nanoTime();
		for (int i = 0; i < values.length; i++) {
			this.asyncLocal.setValue(values[0]);
		}

		writes = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writes);

		long reads = System.nanoTime();
		for (int i = 0; i < values.length; i++) {
			GenericParameterHelper value = asyncLocal.getValue();
		}

		reads = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reads);

		// We don't actually validate the perf here. We just print out the results.
		System.out.format("Creating %s instances took %s ms%n", values.length, creates);
		System.out.format("Saving %s values took %s ms%n", values.length, writes);
		System.out.format("Reading %s values took %s ms%n", values.length, reads);
	}

//        [Fact]
//        public void CallAcrossAppDomainBoundariesWithNonSerializableData()
//        {
//            var otherDomain = AppDomain.CreateDomain("test domain", AppDomain.CurrentDomain.Evidence, AppDomain.CurrentDomain.SetupInformation);
//            try
//            {
//                var proxy = (OtherDomainProxy)otherDomain.CreateInstanceFromAndUnwrap(Assembly.GetExecutingAssembly().Location, typeof(OtherDomainProxy).FullName);
//
//                // Verify we can call it first.
//                proxy.SomeMethod(AppDomain.CurrentDomain.Id);
//
//                // Verify we can call it while AsyncLocal has a non-serializable value.
//                var value = new GenericParameterHelper();
//                this.asyncLocal.Value = value;
//                proxy.SomeMethod(AppDomain.CurrentDomain.Id);
//                Assert.Same(value, this.asyncLocal.Value);
//
//                // Nothing permanently damaged in the ability to set/get values.
//                this.asyncLocal.Value = null;
//                this.asyncLocal.Value = value;
//                Assert.Same(value, this.asyncLocal.Value);
//
//                // Verify we can call it after clearing the value.
//                this.asyncLocal.Value = null;
//                proxy.SomeMethod(AppDomain.CurrentDomain.Id);
//            }
//            finally
//            {
//                AppDomain.Unload(otherDomain);
//            }
//        }

	private static <T> CompletableFuture<Void> independentValuesBetweenContextsHelper(Class<T> clazz) {
		Supplier<T> newInstance = () -> {
			try {
				return clazz.newInstance();
			} catch (InstantiationException | IllegalAccessException ex) {
				throw new RuntimeException(ex);
			}
		};

		AsyncLocal<T> asyncLocal = new AsyncLocal<>();
		AsyncAutoResetEvent player1 = new AsyncAutoResetEvent();
		AsyncAutoResetEvent player2 = new AsyncAutoResetEvent();
		return Async.awaitAsync(
			CompletableFuture.allOf(
				Futures.runAsync(() -> {
					Assert.assertNull(asyncLocal.getValue());
					T value = newInstance.get();
					asyncLocal.setValue(value);
					Assert.assertSame(value, asyncLocal.getValue());
					player1.set();
					return Async.awaitAsync(
						player2.waitAsync(),
						() -> {
							Assert.assertSame(value, asyncLocal.getValue());
							return Futures.completedNull();
						});
				}),
				Futures.runAsync(() -> {
					return Async.awaitAsync(
						player1.waitAsync(),
						() -> {
							Assert.assertNull(asyncLocal.getValue());
							T value = newInstance.get();
							asyncLocal.setValue(value);
							Assert.assertSame(value, asyncLocal.getValue());
							asyncLocal.setValue(null);
							player2.set();
							return Futures.completedNull();
						});
				})),
			() -> {
				Assert.assertNull(asyncLocal.getValue());
				return Futures.completedNull();
			});
	}

//        private class OtherDomainProxy : MarshalByRefObject
//        {
//            internal void SomeMethod(int callingAppDomainId)
//            {
//                Assert.NotEqual(callingAppDomainId, AppDomain.CurrentDomain.Id); // AppDomain boundaries not crossed.
//            }
//        }

}
