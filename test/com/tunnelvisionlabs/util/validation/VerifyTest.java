// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.validation;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class VerifyTest {
	@Rule
	public final ExpectedException thrown = ExpectedException.none();

	@Test
	public void testOperation_ConditionTrue() {
		Verify.operation(true, "expected message");
	}

	@Test
	public void testOperation_ConditionTrue_EmptyMessage() {
		Verify.operation(true, "");
	}

	@Test
	public void testOperation_ConditionTrue_NullMessage() {
		Verify.operation(true, null);
	}

	@Test
	public void testOperation_ConditionFalse() {
		thrown.expect(IllegalStateException.class);
		thrown.expectMessage("expected message");
		Verify.operation(false, "expected message");
	}

	@Test
	public void testOperation_ConditionFalse_EmptyMessage() {
		thrown.expect(IllegalStateException.class);
		thrown.expectMessage("");
		Verify.operation(false, "");
	}

	@Test
	public void testOperation_ConditionFalse_NullMessage() {
		thrown.expect(IllegalStateException.class);
		thrown.expectMessage(CoreMatchers.nullValue(String.class));
		Verify.operation(false, null);
	}

	@Test
	public void testFailOperation() {
		thrown.expect(IllegalStateException.class);
		thrown.expectMessage("expected message");
		Verify.failOperation("expected message");
	}

	@Test
	public void testFailOperation_EmptyMessage() {
		thrown.expect(IllegalStateException.class);
		thrown.expectMessage("");
		Verify.failOperation("");
	}

	@Test
	public void testFailOperation_NullMessage() {
		thrown.expect(IllegalStateException.class);
		thrown.expectMessage(CoreMatchers.nullValue(String.class));
		Verify.failOperation(null);
	}
}
