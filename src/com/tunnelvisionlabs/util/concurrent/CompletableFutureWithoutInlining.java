// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;

/**
 * A {@link CompletableFuture}-derivative that does not inline continuations if so configured.
 *
 * @param <T> The type of the future's resulting value.
 */
class CompletableFutureWithoutInlining<T> extends CompletableFuture<T> {
	/**
	 * A value indicating whether the owner wants to allow continuations of the {@link CompletableFuture} to execute
	 * inline with its completion.
	 */
	private final boolean allowInliningContinuations;

	/**
	 * Constructs a new instance of the {@link CompletableFutureWithoutInlining} class.
	 *
	 * @param allowInliningContinuations {@code true} to allow continuations to be inlined; otherwise {@code false}.
	 */
	CompletableFutureWithoutInlining(boolean allowInliningContinuations) {
		this.allowInliningContinuations = allowInliningContinuations;
	}

	/**
	 * Gets a value indicating whether we can call the completing methods on the base class on our caller's callstack.
	 *
	 * @return {@code true} if our owner allows inlining continuations; {@code false} if our owner does not allow
	 * inlining.
	 */
	private boolean getCanCompleteInline() {
		return this.allowInliningContinuations;
	}

//        //// NOTE: We do NOT define the non-Try completion methods:
//        //// SetResult, SetCanceled, and SetException
//        //// Because their semantic requires that exceptions are thrown
//        //// synchronously, but we cannot guarantee synchronous completion.
//        //// What's more, if an exception were thrown on the threadpool
//        //// it would crash the process.
//
//#if UNUSED
//		new internal void TrySetResult(T value) {
//			if (!this.Task.IsCompleted) {
//				if (this.CanCompleteInline) {
//					base.TrySetResult(value);
//				} else {
//					ThreadPool.QueueUserWorkItem(state => ((TaskCompletionSource<T>)state).TrySetResult(value), this);
//				}
//			}
//		}
//
//		new internal void TrySetCanceled() {
//			if (!this.Task.IsCompleted) {
//				if (this.CanCompleteInline) {
//					base.TrySetCanceled();
//				} else {
//					ThreadPool.QueueUserWorkItem(state => ((TaskCompletionSource<T>)state).TrySetCanceled(), this);
//				}
//			}
//		}
//
//		new internal void TrySetException(Exception exception) {
//			if (!this.Task.IsCompleted) {
//				if (this.CanCompleteInline) {
//					base.TrySetException(exception);
//				} else {
//					ThreadPool.QueueUserWorkItem(state => ((TaskCompletionSource<T>)state).TrySetException(exception), this);
//				}
//			}
//		}
//#endif

//        internal void TrySetCanceled(CancellationToken cancellationToken)
//        {
//            if (!this.Task.IsCompleted)
//            {
//                if (this.CanCompleteInline)
//                {
//                    ThreadingTools.TrySetCanceled(this, cancellationToken);
//                }
//                else
//                {
//                    Tuple<TaskCompletionSourceWithoutInlining<T>, CancellationToken> tuple =
//                        Tuple.Create(this, cancellationToken);
//                    ThreadPool.QueueUserWorkItem(
//                        state =>
//                        {
//                            var s = (Tuple<TaskCompletionSourceWithoutInlining<T>, CancellationToken>)state;
//                            ThreadingTools.TrySetCanceled(s.Item1, s.Item2);
//                        },
//                        tuple);
//                }
//            }
//        }

	void trySetResultToNull() {
		if (!this.isDone()) {
			if (this.getCanCompleteInline()) {
				this.complete(null);
			} else {
				Futures.runAsync(() -> complete(null));
			}
		}
	}

//        /// <summary>
//        /// Modifies the specified flags to include RunContinuationsAsynchronously
//        /// if wanted by the caller and supported by the platform.
//        /// </summary>
//        /// <param name="options">The base options supplied by the caller.</param>
//        /// <param name="allowInliningContinuations"><c>true</c> to allow inlining continuations.</param>
//        /// <returns>The possibly modified flags.</returns>
//        private static TaskCreationOptions AdjustFlags(TaskCreationOptions options, bool allowInliningContinuations)
//        {
//            return (!allowInliningContinuations && LightUps.IsRunContinuationsAsynchronouslySupported)
//                ? (options | LightUps.RunContinuationsAsynchronously)
//                : options;
//        }

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		if (!this.isDone()) {
			if (this.getCanCompleteInline()) {
				if (canCancel()) {
					super.cancel(mayInterruptIfRunning);
				}
			} else {
				Futures.runAsync(
					() -> {
						if (!canCancel()) {
							return;
						}

						CompletableFutureWithoutInlining.super.cancel(mayInterruptIfRunning);
					});
			}
		}

		return false;
	}

	protected boolean canCancel() {
		return true;
	}

	@Override
	public void obtrudeValue(T value) {
		throw new UnsupportedOperationException("Not supported");
	}

	@Override
	public void obtrudeException(Throwable ex) {
		throw new UnsupportedOperationException("Not supported");
	}

}
