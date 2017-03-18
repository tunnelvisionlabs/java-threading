// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import org.junit.Assert;
import org.junit.Test;

public class ExecutionContextTest extends TestBase {

	@Test
	public void testSynchronizationContextDoesNotFlow() {
		SynchronizationContext initial = SynchronizationContext.getCurrent();
		ExecutionContext context = ExecutionContext.capture();
		SynchronizationContext replaced = new SynchronizationContext();
		SynchronizationContext.setSynchronizationContext(replaced);
		ExecutionContext.run(
			ExecutionContext.capture(),
			ignored -> {
				Assert.assertSame(replaced, SynchronizationContext.getCurrent());
			},
			null);
	}

	@Test
	public void testSynchronizationContextRestored() {
		SynchronizationContext initial = SynchronizationContext.getCurrent();
		ExecutionContext.run(
			ExecutionContext.capture(),
			ignored -> {
				SynchronizationContext.setSynchronizationContext(new SynchronizationContext());
			},
			null);

		Assert.assertSame(initial, SynchronizationContext.getCurrent());
	}
}
