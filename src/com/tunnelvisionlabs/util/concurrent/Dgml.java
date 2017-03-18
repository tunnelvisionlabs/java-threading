// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import com.tunnelvisionlabs.util.validation.NotNull;
import com.tunnelvisionlabs.util.validation.Nullable;
import com.tunnelvisionlabs.util.validation.Requires;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 */
enum Dgml {
	;

	/**
	 * The namespace that all DGML nodes appear in.
	 */
	static final String NAMESPACE = "http://schemas.microsoft.com/vs/2009/dgml";

	private static final String NODE_NAME = "Node";
	private static final String NODES_NAME = "Nodes";
	private static final String LINK_NAME = "Link";
	private static final String LINKS_NAME = "Links";
	private static final String STYLES_NAME = "Styles";
	private static final String STYLE_NAME = "Style";

	@NotNull
	static Document create(@NotNull StrongBox<Element> nodes, @NotNull StrongBox<Element> links) throws ParserConfigurationException {
		return create(nodes, links, null, null);
	}

	@NotNull
	static Document create(@NotNull StrongBox<Element> nodes, @NotNull StrongBox<Element> links, @Nullable String layout) throws ParserConfigurationException {
		return create(nodes, links, layout, null);
	}

	@NotNull
	static Document create(@NotNull StrongBox<Element> nodes, @NotNull StrongBox<Element> links, @Nullable String layout, @Nullable String direction) throws ParserConfigurationException {
		if (layout == null) {
			layout = "Sugiyama";
		}

		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
		Document dgml = documentBuilder.newDocument();

		Element root = dgml.createElementNS(NAMESPACE, "DirectedGraph");
		root.setAttribute("Layout", layout);
		if (direction != null) {
			root.setAttribute("GraphDirection", direction);
		}

		dgml.appendChild(root);

		nodes.value = dgml.createElementNS(NAMESPACE, "Nodes");
		links.value = dgml.createElementNS(NAMESPACE, "Links");
		dgml.getDocumentElement().appendChild(nodes.value);
		dgml.getDocumentElement().appendChild(links.value);
		withCategories(dgml, category(dgml, "Contains", null, null, null, null, false, /*isContainment:*/ true));
		return dgml;
	}

	@NotNull
	private static Element getRootElement(@NotNull Document document, @NotNull String namespace, @NotNull String localName) {
		Requires.notNull(document, "document");
		Requires.notNull(namespace, "namespace");
		Requires.notNull(localName, "localName");

		NodeList container = document.getDocumentElement().getElementsByTagNameNS(namespace, localName);
		if (container == null || container.getLength() == 0) {
			Element child = document.createElementNS(namespace, localName);
			document.getDocumentElement().appendChild(child);
			return child;
		}

		return (Element)container.item(0);
	}

	@NotNull
	private static Element getRootElement(@NotNull Document document, @NotNull String elementName) {
		Requires.notNull(document, "document");
		Requires.notNullOrEmpty(elementName, "elementName");

		return getRootElement(document, NAMESPACE, elementName);
	}

	@NotNull
	static Document withCategories(@NotNull Document document, @NotNull String... categories) {
		Requires.notNull(document, "document");
		Requires.notNull(categories, "categories");

		Element rootElement = getRootElement(document, "Categories");
		for (String category : categories) {
			rootElement.appendChild(category(document, category, null, null, null, null, false, false));
		}

		return document;
	}

	@NotNull
	static Document withCategories(@NotNull Document document, @NotNull Element... categories) {
		Requires.notNull(document, "document");
		Requires.notNull(categories, "categories");

		Element rootElement = getRootElement(document, "Categories");
		for (Element category : categories) {
			rootElement.appendChild(category);
		}

		return document;
	}

	@NotNull
	static Element node(@NotNull Document document, @Nullable String id, @Nullable String label, @Nullable String group) {
		Element element = document.createElementNS(NAMESPACE, NODE_NAME);

		if (id != null && !id.isEmpty()) {
			element.setAttribute("Id", id);
		}

		if (label != null && !label.isEmpty()) {
			element.setAttribute("Label", label);
		}

		if (group != null && !group.isEmpty()) {
			element.setAttribute("Group", group);
		}

		return element;
	}

	@NotNull
	static Document withNode(@NotNull Document document, @NotNull Element node) {
		Requires.notNull(document, "document");
		Requires.notNull(node, "node");

		Element nodes = getRootElement(document, NODES_NAME);
		nodes.appendChild(node);
		return document;
	}

	@NotNull
	static Element link(@NotNull Document document, @NotNull String source, @NotNull String target) {
		Requires.notNull(document, "document");
		Requires.notNullOrEmpty(source, "source");
		Requires.notNullOrEmpty(target, "target");

		Element element = document.createElementNS(NAMESPACE, LINK_NAME);
		element.setAttribute("Source", source);
		element.setAttribute("Target", target);
		return element;
	}

	@NotNull
	static Element link(@NotNull Element source, @NotNull Element target) {
		return link(source.getOwnerDocument(), source.getAttribute("Id"), target.getAttribute("Id"));
	}

	@NotNull
	static Document withLink(@NotNull Document document, @NotNull Element link) {
		Requires.notNull(document, "document");
		Requires.notNull(link, "link");

		Element links = getRootElement(document, LINKS_NAME);
		links.appendChild(link);
		return document;
	}

	@NotNull
	static Element category(@NotNull Document document, @NotNull String id, @Nullable String label, @Nullable String background, @Nullable String foreground, @Nullable String icon, boolean isTag, boolean isContainment) {
		Requires.notNullOrEmpty(id, "id");

		Element category = document.createElementNS(NAMESPACE, "Category");
		category.setAttribute("Id", id);
		if (label != null && !label.isEmpty()) {
			category.setAttribute("Label", label);
		}

		if (background != null && !background.isEmpty()) {
			category.setAttribute("Background", background);
		}

		if (foreground != null && !foreground.isEmpty()) {
			category.setAttribute("Foreground", foreground);
		}

		if (icon != null && !icon.isEmpty()) {
			category.setAttribute("Icon", icon);
		}

		if (isTag) {
			category.setAttribute("IsTag", "True");
		}

		if (isContainment) {
			category.setAttribute("IsContainment", "True");
		}

		return category;
	}

	@NotNull
	static Element comment(@NotNull Document document, String label) {
		return withCategories(node(document, null, /*label:*/ label, null), "Comment");
	}

//        internal static XElement Container(string id, string label = null)
//        {
//            return Node(id, label, group: "Expanded");
//        }
//
//        internal static XDocument WithContainers(this XDocument document, IEnumerable<XElement> containers)
//        {
//            foreach (var container in containers)
//            {
//                WithNode(document, container);
//            }
//
//            return document;
//        }
//
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Performance", "CA1811:AvoidUncalledPrivateCode")]
//        internal static XElement ContainedBy(this XElement node, XElement container)
//        {
//            Requires.NotNull(node, nameof(node));
//            Requires.NotNull(container, nameof(container));
//
//            Link(container, node).WithCategories("Contains");
//            return node;
//        }
//
//        internal static XElement ContainedBy(this XElement node, string containerId, XDocument document)
//        {
//            Requires.NotNull(node, nameof(node));
//            Requires.NotNullOrEmpty(containerId, nameof(containerId));
//
//            document.WithLink(Link(containerId, node.Attribute("Id").Value).WithCategories("Contains"));
//            return node;
//        }

	/**
	 * Adds categories to a DGML node or link.
	 *
	 * @param element The node or link to add categories to.
	 * @param categories The categories to add.
	 * @return The same node that was passed in. To enable "fluent" syntax.
	 */
	@NotNull
	static Element withCategories(@NotNull Element element, @NotNull String... categories) {
		Requires.notNull(element, "element");

		for (String category : categories) {
			if (element.getAttribute("Category") == null) {
				element.setAttribute("Category", category);
			} else {
				Document document = element.getOwnerDocument();
				Element child = document.createElementNS(NAMESPACE, "Category");
				child.setAttribute("Ref", category);
				element.appendChild(child);
			}
		}

		return element;
	}

//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Performance", "CA1811:AvoidUncalledPrivateCode")]
//        internal static XDocument WithStyle(this XDocument document, string categoryId, IEnumerable<KeyValuePair<string, string>> properties, string targetType = "Node")
//        {
//            Requires.NotNull(document, nameof(document));
//            Requires.NotNullOrEmpty(categoryId, nameof(categoryId));
//            Requires.NotNull(properties, nameof(properties));
//            Requires.NotNullOrEmpty(targetType, nameof(targetType));
//
//            var container = document.Root.Element(StylesName);
//            if (container == null)
//            {
//                document.Root.Add(container = new XElement(StylesName));
//            }
//
//            var style = new XElement(StyleName,
//                new XAttribute("TargetType", targetType),
//                new XAttribute("GroupLabel", categoryId),
//                new XElement(XName.Get("Condition", Namespace), new XAttribute("Expression", "HasCategory('" + categoryId + "')")));
//            style.Add(properties.Select(p => new XElement(XName.Get("Setter", Namespace), new XAttribute("Property", p.Key), new XAttribute("Value", p.Value))));
//
//            container.Add(style);
//
//            return document;
//        }
//
//        [System.Diagnostics.CodeAnalysis.SuppressMessage("Microsoft.Performance", "CA1811:AvoidUncalledPrivateCode")]
//        internal static XDocument WithStyle(this XDocument document, string categoryId, string targetType = "Node", string foreground = null, string background = null, string icon = null)
//        {
//            var properties = new Dictionary<string, string>();
//            if (!string.IsNullOrEmpty(foreground))
//            {
//                properties.Add("Foreground", foreground);
//            }
//
//            if (!string.IsNullOrEmpty(background))
//            {
//                properties.Add("Background", background);
//            }
//
//            if (!string.IsNullOrEmpty(icon))
//            {
//                properties.Add("Icon", icon);
//            }
//
//            return WithStyle(document, categoryId, properties, targetType);
//        }
}
