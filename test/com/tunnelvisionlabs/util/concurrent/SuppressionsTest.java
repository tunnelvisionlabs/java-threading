// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.Matchers.emptyArray;

public class SuppressionsTest {
	@Test
	public void testNoMembers() {
		Assert.assertThat(Suppressions.values(), emptyArray());
	}
}
