// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.validation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RequiresTest {
	@Rule
	public final ExpectedException thrown = ExpectedException.none();

	@Test
	public void testNotNull_NotNullValue_WithParameter() {
		Requires.notNull(new Object(), "parameter");
	}

	@Test
	public void testNotNull_NotNullValue_WithEmptyParameter() {
		Requires.notNull(new Object(), "");
	}

	@Test
	public void testNotNull_NotNullValue_WithNullParameter() {
		Requires.notNull(new Object(), null);
	}

	@Test
	public void testNotNull_NullValue_WithParameter() {
		thrown.expect(NullPointerException.class);
		thrown.expectMessage("parameter cannot be null");
		Requires.notNull(null, "parameter");
	}

	@Test
	public void testNotNull_NullValue_WithEmptyParameter() {
		thrown.expect(NullPointerException.class);
		thrown.expectMessage(" cannot be null");
		Requires.notNull(null, "");
	}

	@Test
	public void testNotNull_NullValue_WithNullParameter() {
		thrown.expect(NullPointerException.class);
		thrown.expectMessage("null cannot be null");
		Requires.notNull(null, null);
	}

	@Test
	public void testNotNullOrEmpty_WithValue_WithParameter() {
		Requires.notNullOrEmpty("value", "parameter");
	}

	@Test
	public void testNotNullOrEmpty_WithValue_WithEmptyParameter() {
		Requires.notNullOrEmpty("value", "");
	}

	@Test
	public void testNotNullOrEmpty_WithValue_WithNullParameter() {
		Requires.notNullOrEmpty("value", null);
	}

	@Test
	public void testNotNullOrEmpty_NullValue_WithParameter() {
		thrown.expect(NullPointerException.class);
		thrown.expectMessage("parameter cannot be null");
		Requires.notNullOrEmpty(null, "parameter");
	}

	@Test
	public void testNotNullOrEmpty_NullValue_WithEmptyParameter() {
		thrown.expect(NullPointerException.class);
		thrown.expectMessage(" cannot be null");
		Requires.notNullOrEmpty(null, "");
	}

	@Test
	public void testNotNullOrEmpty_NullValue_WithNullParameter() {
		thrown.expect(NullPointerException.class);
		thrown.expectMessage("null cannot be null");
		Requires.notNullOrEmpty(null, null);
	}

	@Test
	public void testNotNullOrEmpty_EmptyValue_WithParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("parameter: cannot be empty");
		Requires.notNullOrEmpty("", "parameter");
	}

	@Test
	public void testNotNullOrEmpty_EmptyValue_WithEmptyParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage(": cannot be empty");
		Requires.notNullOrEmpty("", "");
	}

	@Test
	public void testNotNullOrEmpty_EmptyValue_WithNullParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("null: cannot be empty");
		Requires.notNullOrEmpty("", null);
	}

	@Test
	public void testArgument_ConditionTrue_WithParameter() {
		Requires.argument(true, "parameter", "custom message");
	}

	@Test
	public void testArgument_ConditionTrue_WithEmptyParameter() {
		Requires.argument(true, "", "custom message");
	}

	@Test
	public void testArgument_ConditionTrue_WithNullParameter() {
		Requires.argument(true, null, "custom message");
	}

	@Test
	public void testArgument_ConditionFalse_WithParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("parameter: custom message");
		Requires.argument(false, "parameter", "custom message");
	}

	@Test
	public void testArgument_ConditionFalse_WithEmptyParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage(": custom message");
		Requires.argument(false, "", "custom message");
	}

	@Test
	public void testArgument_ConditionFalse_WithNullParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("null: custom message");
		Requires.argument(false, null, "custom message");
	}

	@Test
	public void testRange_ConditionTrue_WithParameter() {
		Requires.range(true, "parameter");
	}

	@Test
	public void testRange_ConditionTrue_WithEmptyParameter() {
		Requires.range(true, "");
	}

	@Test
	public void testRange_ConditionTrue_WithNullParameter() {
		Requires.range(true, null);
	}

	@Test
	public void testRange_ConditionFalse_WithParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("parameter is out of range");
		Requires.range(false, "parameter");
	}

	@Test
	public void testRange_ConditionFalse_WithEmptyParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage(" is out of range");
		Requires.range(false, "");
	}

	@Test
	public void testRange_ConditionFalse_WithNullParameter() {
		thrown.expect(IllegalArgumentException.class);
		thrown.expectMessage("null is out of range");
		Requires.range(false, null);
	}

}
