package com.adobe.dp.css;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.adobe.dp.xml.util.SMap;
import com.adobe.dp.xml.util.SMapAttributesAdapter;
import com.adobe.dp.xml.util.SMapImpl;
import com.adobe.dp.xml.util.XMLSerializer;

public class CascadeEngine {

	CascadeResult result;

	Vector matcherLists = new Vector();

	int depth;

	int order;

	static class CascadeValue {
		int specificity;

		int importance;

		int order;

		Object value;

		public CascadeValue(Object value, int specificity, int importance, int order) {
			this.value = value;
			this.specificity = specificity;
			this.importance = importance;
			this.order = order;
		}

		int compareSpecificity(CascadeValue other) {
			if (specificity != other.specificity)
				return specificity - other.specificity;
			if (importance != other.importance)
				return importance - other.importance;
			return order - other.order;
		}
	}

	static class MatcherList {
		Vector matchers = new Vector();

		Set mediaList;

		CSSStylesheet stylesheet;

		MatcherList(CSSStylesheet stylesheet, Set mediaList) {
			this.stylesheet = stylesheet;
			this.mediaList = mediaList;
		}

		void addSelectorRule(SelectorRule rule, int depth, int order) {
			for (int i = 0; i < rule.selectors.length; i++) {
				Selector s = rule.selectors[i];
				ElementMatcher matcher = s.getElementMatcher();
				while (depth > 0) {
					matcher.pushElement(null, "*", null);
					depth--;
				}
				matchers.add(new MatcherRule(matcher, rule, order));
			}
		}
	}

	static class MatcherRule {
		ElementMatcher matcher;

		SelectorRule rule;

		int order;

		public MatcherRule(ElementMatcher matcher, SelectorRule rule, int order) {
			super();
			this.matcher = matcher;
			this.rule = rule;
			this.order = order;
		}
	}

	public CascadeEngine() {

	}

	public void add(CSSStylesheet stylesheet, Set mediaList) {
		MatcherList ml = new MatcherList(stylesheet, mediaList);
		Iterator statements = stylesheet.statements.iterator();
		while (statements.hasNext()) {
			Object statement = statements.next();
			if (statement instanceof SelectorRule) {
				ml.addSelectorRule((SelectorRule) statement, depth, order++);
			}
		}
		matcherLists.add(ml);
	}

	private void applyRule(Selector selector, int order, BaseRule rule, String pseudoElement, Set mediaList) {
		int specificity = selector.getSpecificity();
		applyRule(specificity, order, rule, pseudoElement, mediaList);
	}

	public void applyInlineRule(InlineRule rule) {
		applyRule(0x7F000000, order, rule, null, null);
	}

	private void applyRule(int specificity, int order, BaseRule rule, String pseudoElement, Set mediaList) {
		if( rule == null || rule.properties == null )
			return;
		Iterator entries = rule.properties.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry entry = (Map.Entry) entries.next();
			String prop = (String) entry.getKey();
			int importance = 0;
			Object value = entry.getValue();
			if (value instanceof CSSImportant)
				importance = 1;
			Iterator it = (mediaList == null ? null : mediaList.iterator());
			do {
				ElementProperties props;
				if (it == null)
					props = result.getProperties();
				else
					props = result.getPropertiesForMedia((String) it.next());
				InlineRule style;
				if (pseudoElement == null)
					style = props.getPropertySet();
				else
					style = props.getPropertySetForPseudoElement(pseudoElement);
				CascadeValue existing = (CascadeValue) style.get(prop);
				CascadeValue curr = new CascadeValue(value, specificity, importance, order);
				if (existing == null || curr.compareSpecificity(existing) > 0)
					style.set(prop, curr);
			} while (it != null && it.hasNext());
		}
	}

	/**
	 * Styles an element with a given namespace, name and attributes
	 * 
	 * @param ns
	 *            element's namespace
	 * @param name
	 *            element's local name
	 * @param attrs
	 *            element's attributes
	 */
	public void pushElement(String ns, String name, SMap attrs) {
		depth++;
		result = new CascadeResult();
		Iterator mli = matcherLists.iterator();
		while (mli.hasNext()) {
			MatcherList ml = (MatcherList) mli.next();
			Iterator matchers = ml.matchers.iterator();
			while (matchers.hasNext()) {
				MatcherRule mr = (MatcherRule) matchers.next();
				MatchResult res = mr.matcher.pushElement(ns, name, attrs);
				if (res != null)
					applyRule(mr.matcher.getSelector(), mr.order, mr.rule, res.pseudoElement, ml.mediaList);
			}
		}
	}

	/**
	 * Finish element's processing. Note that pushElement/popElement calls
	 * should correspond to the elements nesting.
	 */
	public void popElement() {
		depth--;
		Iterator mli = matcherLists.iterator();
		while (mli.hasNext()) {
			MatcherList ml = (MatcherList) mli.next();
			Iterator matchers = ml.matchers.iterator();
			while (matchers.hasNext()) {
				MatcherRule mr = (MatcherRule) matchers.next();
				mr.matcher.popElement();
			}
		}
	}

	BaseRule makeRule(HashMap map) {
		if (map.isEmpty())
			return null;
		BaseRule rule = new InlineRule();
		Iterator entries = map.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry entry = (Map.Entry) entries.next();
			String prop = (String) entry.getKey();
			CascadeValue cv = (CascadeValue) entry.getValue();
			rule.set(prop, cv.value);
		}
		return rule;
	}

	public CascadeResult getCascadeResult() {
		CascadeResult r = new CascadeResult();
		Iterator mediaList = result.media();
		ElementProperties elementProperties = result.getProperties();
		if (mediaList != null) {
			while (mediaList.hasNext()) {
				String media = (String) mediaList.next();
				ElementProperties mediaProps = result.getPropertiesForMedia(media);
				Iterator pel = mediaProps.pseudoElements();
				if (pel != null) {
					while (pel.hasNext()) {
						String pseudoElement = (String) pel.next();
						InlineRule mps = mediaProps.getPropertySetForPseudoElement(pseudoElement);
						InlineRule ps = elementProperties.getPropertySetForPseudoElement(pseudoElement);
						Iterator properties = mps.properties();
						while (properties.hasNext()) {
							String property = (String) properties.next();
							CascadeValue mediaSpecific = (CascadeValue) mps.get(property);
							CascadeValue generic = (CascadeValue) ps.get(property);
							if (generic == null || mediaSpecific.compareSpecificity(generic) > 0) {
								Object value = mediaSpecific.value;
								ElementProperties rm = r.getPropertiesForMedia(media);
								rm.getPropertySetForPseudoElement(pseudoElement).set(property, value);
							}

						}
					}
				}
				InlineRule mps = mediaProps.getPropertySet();
				InlineRule ps = elementProperties.getPropertySet();
				Iterator properties = mps.properties();
				while (properties.hasNext()) {
					String property = (String) properties.next();
					CascadeValue mediaSpecific = (CascadeValue) mps.get(property);
					CascadeValue generic = (CascadeValue) ps.get(property);
					if (generic == null || mediaSpecific.compareSpecificity(generic) > 0) {
						Object value = mediaSpecific.value;
						ElementProperties rm = r.getPropertiesForMedia(media);
						rm.getPropertySet().set(property, value);
					}
				}
			}
		}
		Iterator pel = elementProperties.pseudoElements();
		if (pel != null) {
			while (pel.hasNext()) {
				String pseudoElement = (String) pel.next();
				InlineRule ps = elementProperties.getPropertySetForPseudoElement(pseudoElement);
				Iterator properties = ps.properties();
				while (properties.hasNext()) {
					String property = (String) properties.next();
					CascadeValue cv = (CascadeValue) ps.get(property);
					ElementProperties rp = r.getProperties();
					rp.getPropertySetForPseudoElement(pseudoElement).set(property, cv.value);
				}
			}
		}
		InlineRule ps = elementProperties.getPropertySet();
		Iterator properties = ps.properties();
		while (properties.hasNext()) {
			String property = (String) properties.next();
			CascadeValue cv = (CascadeValue) ps.get(property);
			ElementProperties rp = r.getProperties();
			rp.getPropertySet().set(property, cv.value);
		}
		return r;
	}

	public static void main(String[] args) {
		try {
			InputStream in = new FileInputStream(args[0]);
			InputStream cssin = new FileInputStream(args[1]);
			CSSStylesheet stylesheet = (new CSSParser()).readStylesheet(cssin);

			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(true);

			final CascadeEngine styler = new CascadeEngine();
			styler.add(stylesheet, null);

			final XMLSerializer ser = new XMLSerializer(System.out);
			ser.startDocument("1.0", "UTF-8");

			SAXParser parser = factory.newSAXParser();
			XMLReader reader = parser.getXMLReader();
			reader.setContentHandler(new ContentHandler() {
				public void characters(char[] text, int offset, int len) throws SAXException {
					ser.text(text, offset, len);
				}

				public void endDocument() throws SAXException {
				}

				public void endElement(String ns, String local, String qname) throws SAXException {
					styler.popElement();
					ser.endElement(ns, local);
				}

				public void endPrefixMapping(String arg0) throws SAXException {
				}

				public void ignorableWhitespace(char[] arg0, int arg1, int arg2) throws SAXException {
				}

				public void processingInstruction(String arg0, String arg1) throws SAXException {
				}

				public void setDocumentLocator(Locator arg0) {
				}

				public void skippedEntity(String arg0) throws SAXException {
				}

				public void startDocument() throws SAXException {
				}

				public void startElement(String ns, String local, String qname, Attributes attrs) throws SAXException {
					SMapImpl smap = new SMapImpl(new SMapAttributesAdapter(attrs));
					styler.pushElement(ns, local, smap);
					BaseRule es = styler.getCascadeResult().getProperties().getPropertySet();
					smap.put(null, "style", (es.isEmpty() ? null : es.toString()));
					ser.startElement(ns, local, smap, qname.indexOf(':') < 0);
				}

				public void startPrefixMapping(String arg0, String arg1) throws SAXException {
				}

			});

			reader.parse(new InputSource(in));
			ser.endDocument();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
