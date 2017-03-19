// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.function.Function;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.Matchers.emptyArray;

public class FunctionsTest {
	@Test
	public void testNoMembers() {
		Assert.assertThat(Functions.values(), emptyArray());
	}

	@Test
	public void testIdentityBehavior() {
		Assert.assertNull(Function.identity().apply(null));

		Object expected = new Object();
		Assert.assertSame(expected, Function.identity().apply(expected));
	}

	@Test
	public void testIdentityCaching() {
		Function<Integer, Integer> integerIdentity = Functions.identity();
		Function<Exception, Exception> exceptionIdentity = Functions.identity();
		Assert.assertSame(integerIdentity, exceptionIdentity);
	}
}
