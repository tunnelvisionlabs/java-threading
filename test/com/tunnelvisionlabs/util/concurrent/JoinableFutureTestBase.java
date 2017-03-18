// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Verify;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
public abstract class JoinableFutureTestBase extends TestBase {
	protected static final String DGML_NAMESPACE = "http://schemas.microsoft.com/vs/2009/dgml";

	protected JoinableFutureContext context;
	protected JoinableFutureFactory asyncPump;
	protected JoinableFutureCollection joinableCollection;

	protected Thread originalThread;
	protected SynchronizationContext dispatcherContext;
	protected SingleThreadedSynchronizationContext.Frame testFrame;

	@Before
	public final void setup() {
		this.dispatcherContext = SingleThreadedSynchronizationContext.create();
		SynchronizationContext.setSynchronizationContext(this.dispatcherContext);
		this.context = this.createJoinableFutureContext();
		this.joinableCollection = this.context.createCollection();
		this.asyncPump = this.context.createFactory(this.joinableCollection);
		this.originalThread = Thread.currentThread();
		this.testFrame = SingleThreadedSynchronizationContext.newFrame();

//            // Suppress the assert dialog that appears and causes test runs to hang.
//            Trace.Listeners.OfType<DefaultTraceListener>().Single().AssertUiEnabled = false;
	}

	@After
	public final void teardown() {
		Assert.assertTrue(ForkJoinPool.commonPool().awaitQuiescence(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
	}

	@NotNull
	protected JoinableFutureContext createJoinableFutureContext() {
		return new JoinableFutureContext();
	}

	protected final int getPendingFuturesCount() {
		HangReportContributor hangContributor = this.context;
		HangReportContribution contribution = hangContributor.getHangReport();

		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
		documentBuilderFactory.setNamespaceAware(true);

		try {
			DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
			Document dgml = builder.parse(new ByteArrayInputStream(contribution.getContent().getBytes(Charset.forName("UTF-8"))));

			List<Element> nodes = descendants(dgml.getDocumentElement(), DGML_NAMESPACE, "Node");
			return (int)nodes.stream()
				.filter(input -> {
					NodeList categories = input.getElementsByTagNameNS(DGML_NAMESPACE, "Category");
					for (int i = 0; i < categories.getLength(); i++) {
						NamedNodeMap attributes = categories.item(i).getAttributes();
						for (int j = 0; j < attributes.getLength(); j++) {
							if ("Task".equals(attributes.item(j).getNodeValue())) {
								return true;
							}
						}
					}

					return false;
				})
				.count();
		} catch (ParserConfigurationException | SAXException | IOException ex) {
			throw new CompletionException(ex);
		}
	}

	@NotNull
	private static List<Element> descendants(@NotNull Element element, @NotNull String namespace, @NotNull String localName) {
		List<Element> result = new ArrayList<>();
		if (namespace.equals(element.getNamespaceURI())
			&& localName.equals(element.getLocalName())) {
			result.add(element);
		}

		for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (!(child instanceof Element)) {
				continue;
			}

			result.addAll(descendants((Element)child, namespace, localName));
		}

		return result;
	}

	protected final void simulateUIThread(Supplier<CompletableFuture<?>> testMethod) {
		Verify.operation(this.originalThread == Thread.currentThread(), "We can only simulate the UI thread if you're already on it (the starting thread for the test).");

		StrongBox<Throwable> failure = new StrongBox<>(null);
		this.dispatcherContext.post(
			state -> {
				Async.finallyAsync(
					Async.awaitAsync(testMethod.get())
					.exceptionally(ex -> {
						failure.value = ex;
						return null;
					}),
					() -> testFrame.setContinue(false));
			},
			null);

		SingleThreadedSynchronizationContext.pushFrame(this.dispatcherContext, this.testFrame);
		if (failure.value != null) {
			// Rethrow original exception without rewriting the callstack.
			throw new CompletionException(failure.value);
		}
	}

	protected final void pushFrame() {
		SingleThreadedSynchronizationContext.pushFrame(this.dispatcherContext, this.testFrame);
	}

	protected final void pushFrameUntilQueueIsEmpty() {
		this.dispatcherContext.post(s -> this.testFrame.setContinue(false), null);
		this.pushFrame();
	}
}
