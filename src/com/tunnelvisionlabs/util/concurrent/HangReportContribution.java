// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
import java.util.Arrays;
import java.util.List;

/**
 * A contribution to an aggregate hang report.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public class HangReportContribution {
	private String content;
	private String contentType;
	private String contentName;
	private List<? extends HangReportContribution> nestedReports;

	/**
	 * Constructs a new instance of the {@link HangReportContribution} class.
	 *
	 * @param conten The content for the hang report.
	 * @param contentType The MIME type of the attached content.
	 * @param contentName The suggested filename of the content when it is attached in a report.
	 */
	public HangReportContribution(@NotNull String content, String contentType, String contentName) {
		this(content, contentType, contentName, (HangReportContribution[])null);
	}

	/**
	 * Constructs a new instance of the {@link HangReportContribution} class.
	 *
	 * @param conten The content for the hang report.
	 * @param contentType The MIME type of the attached content.
	 * @param contentName The suggested filename of the content when it is attached in a report.
	 * @param nestedReports The nested reports, if any.
	 */
	public HangReportContribution(@NotNull String content, String contentType, String contentName, @Nullable HangReportContribution... nestedReports) {
		Requires.notNull(content, "content");

		this.content = content;
		this.contentType = contentType;
		this.contentName = contentName;
		this.nestedReports = nestedReports != null ? Arrays.asList(nestedReports) : null;
	}

	/**
	 * Gets the content of the hang report.
	 */
	public final String getContent() {
		return content;
	}

	/**
	 * Gets the MIME type for the content.
	 */
	public final String getContentType() {
		return contentType;
	}

	/**
	 * Gets the suggested filename for the content.
	 */
	public final String getContentName() {
		return contentName;
	}

	/**
	 * Gets the nested hang reports, if any.
	 *
	 * @return A read only collection, or {@code null}.
	 */
	public final List<? extends HangReportContribution> getNestedReports() {
		return nestedReports;
	}
}
