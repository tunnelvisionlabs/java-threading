// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.concurrent.CompletableFuture;

/**
 * Utility methods for working across threads.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public enum ThreadingTools {
	;

//        /// <summary>
//        /// Optimistically performs some value transformation based on some field and tries to apply it back to the field,
//        /// retrying as many times as necessary until no other thread is manipulating the same field.
//        /// </summary>
//        /// <typeparam name="T">The type of data.</typeparam>
//        /// <param name="hotLocation">The field that may be manipulated by multiple threads.</param>
//        /// <param name="applyChange">A function that receives the unchanged value and returns the changed value.</param>
//        /// <returns>
//        /// <c>true</c> if the location's value is changed by applying the result of the <paramref name="applyChange"/> function;
//        /// <c>false</c> if the location's value remained the same because the last invocation of <paramref name="applyChange"/> returned the existing value.
//        /// </returns>
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Design", "CA1045:DoNotPassTypesByReference", MessageId = "0#")]
//        public static bool ApplyChangeOptimistically<T>(ref T hotLocation, Func<T, T> applyChange)
//            where T : class
//        {
//            Requires.NotNull(applyChange, nameof(applyChange));
//
//            bool successful;
//            do
//            {
//                T oldValue = Volatile.Read(ref hotLocation);
//                T newValue = applyChange(oldValue);
//                if (object.ReferenceEquals(oldValue, newValue))
//                {
//                    // No change was actually required.
//                    return false;
//                }
//
//                T actualOldValue = Interlocked.CompareExchange<T>(ref hotLocation, newValue, oldValue);
//                successful = object.ReferenceEquals(oldValue, actualOldValue);
//            }
//            while (!successful);
//
//            return true;
//        }

	/**
	 * Wraps a future with one that will complete as canceled based on a cancellation token, allowing someone to await
	 * a task but be able to break out early by canceling the token.
	 *
	 * @param <T> The type of value returned by the future.
	 * @param future The future to wrap.
	 * @param cancellationToken The token that can be canceled to break out of the await.
	 * @return The wrapping future.
	 */
	@NotNull
	public static <T> CompletableFuture<T> withCancellation(@NotNull CompletableFuture<T> future, @NotNull CancellationToken cancellationToken) {
		Requires.notNull(future, "task");

		if (!cancellationToken.canBeCancelled() || future.isDone()) {
			return future;
		}

		if (cancellationToken.isCancellationRequested()) {
			return Futures.completedCancelled();
		}

		return withCancellationSlow(future, cancellationToken);
	}

//        /// <summary>
//        /// Applies the specified <see cref="SynchronizationContext"/> to the caller's context.
//        /// </summary>
//        /// <param name="syncContext">The synchronization context to apply.</param>
//        /// <param name="checkForChangesOnRevert">A value indicating whether to check that the applied SyncContext is still the current one when the original is restored.</param>
//        public static SpecializedSyncContext Apply(this SynchronizationContext syncContext, bool checkForChangesOnRevert = true)
//        {
//            return SpecializedSyncContext.Apply(syncContext, checkForChangesOnRevert);
//        }
//
//        internal static bool TrySetCanceled<T>(this TaskCompletionSource<T> tcs, CancellationToken cancellationToken)
//        {
//            return LightUps<T>.TrySetCanceled != null
//                ? LightUps<T>.TrySetCanceled(tcs, cancellationToken)
//                : tcs.TrySetCanceled();
//        }
//
//        internal static Task TaskFromCanceled(CancellationToken cancellationToken)
//        {
//            return TaskFromCanceled<EmptyStruct>(cancellationToken);
//        }
//
//        internal static Task<T> TaskFromCanceled<T>(CancellationToken cancellationToken)
//        {
//            var tcs = new TaskCompletionSource<T>();
//            tcs.TrySetCanceled(cancellationToken);
//            return tcs.Task;
//        }
//
//        internal static Task TaskFromException(Exception exception)
//        {
//            return TaskFromException<EmptyStruct>(exception);
//        }
//
//        internal static Task<T> TaskFromException<T>(Exception exception)
//        {
//            var tcs = new TaskCompletionSource<T>();
//            tcs.TrySetException(exception);
//            return tcs.Task;
//        }

	/**
	 * Wraps a future with one that will complete as cancelled based on a cancellation token, allowing someone to await
	 * a task but be able to break out early by cancelling the token.
	 *
	 * @param <T> The type of value returned by the future.
	 * @param future The future to wrap.
	 * @param cancellationFuture The token that can be cancelled to break out of the await.
	 * @return The wrapping future.
	 */
	@NotNull
	private static <T> CompletableFuture<T> withCancellationSlow(@NotNull CompletableFuture<T> future, @NotNull CancellationToken cancellationToken) {
		return Async.runAsync(() -> {
			assert future != null;
			assert cancellationToken != null;

			CompletableFuture<T> cancellationFuture = new CompletableFuture<>();
			return Async.usingAsync(
				cancellationToken.register(f -> f.cancel(false), cancellationFuture),
				() -> {
					return Async.awaitAsync(
						Async.configureAwait(Async.whenAny(future, cancellationFuture), false),
						completedFuture -> {
							if (future != completedFuture) {
								if (cancellationFuture.isDone()) {
									return Futures.completedCancelled();
								}
							}

							// Rethrow any fault/cancellation exception, even if we awaited above.
							// But if we skipped the above if branch, this will actually yield
							// on an incompleted future.
							return Async.awaitAsync(Async.configureAwait(future, false));
						});
				});
		});
	}
}
