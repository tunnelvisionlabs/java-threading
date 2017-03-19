// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AggregateExceptionTest {
	@Rule
	public final ExpectedException thrown = ExpectedException.none();

	@Test
	public void testAggregateException_NullCausesCause() {
		List<Throwable> causes = null;

		thrown.expect(NullPointerException.class);
		new AggregateException(causes);
	}

	@Test
	public void testAggregateException_NoCause() {
		List<Throwable> causes = Collections.emptyList();

		thrown.expect(IndexOutOfBoundsException.class);
		new AggregateException(causes);
	}

	@Test
	public void testAggregateException_SingleCause() {
		List<Throwable> causes = Collections.singletonList(new IllegalArgumentException());

		AggregateException ex = new AggregateException(causes);
		Assert.assertSame(ex.getCause(), causes.get(0));
	}

	@Test
	public void testAggregateException_MultipleCauses() {
		List<Throwable> causes = Arrays.asList(new IllegalArgumentException(), new IllegalStateException());

		AggregateException ex = new AggregateException(causes);
		Assert.assertSame(ex.getCause(), causes.get(0));
	}
}
