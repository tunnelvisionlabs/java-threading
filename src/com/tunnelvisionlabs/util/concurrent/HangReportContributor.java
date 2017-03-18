// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.Nullable;

/**
 * Provides a facility to produce reports that may be useful when analyzing hangs.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public interface HangReportContributor {
	/**
	 * Contributes data for a hang report.
	 *
	 * @return The hang report contribution. {@code null} values should be ignored.
	 */
	@Nullable
	HangReportContribution getHangReport();
}
