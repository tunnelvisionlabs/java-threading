// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.validation;

import org.junit.Test;

public class ReportTest {
	@Test
	public void testFailDoesNothing() {
		Report.fail("Message");
		Report.fail("Message %n", 3);
	}

	@Test
	public void testReportIfDoesNothing_ConditionTrue() {
		testReportIfDoesNothing(true);
	}

	@Test
	public void testReportIfDoesNothing_ConditionFalse() {
		testReportIfDoesNothing(false);
	}

	private void testReportIfDoesNothing(boolean condition) {
		Report.reportIf(condition);
		Report.reportIf(condition, "Message");
		Report.reportIf(condition, "Message %n", 3);
	}
}
