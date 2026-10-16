package audiogear.feed;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads RSS 2.0 (XenForo forums) and Atom (Reddit, YouTube) feeds. DOCTYPEs are refused outright, so a feed can't
 * pull in local files or external entities.
 */
public final class RssReader {
    private RssReader() {}

    public static List<FeedItem> parse(String xml) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            f.setNamespaceAware(true);
            doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IOException("Unreadable feed: " + e.getMessage(), e);
        }
        List<FeedItem> out = new ArrayList<>();
        for (Element item : descendants(doc.getDocumentElement(), "item")) {
            String url = text(item, "link"), title = text(item, "title");
            if (url != null && title != null) out.add(new FeedItem(url, title, rfc1123Date(text(item, "pubDate"))));
        }
        for (Element entry : descendants(doc.getDocumentElement(), "entry")) {
            String url = atomLink(entry), title = text(entry, "title");
            String date = text(entry, "published");
            if (date == null) date = text(entry, "updated");
            if (url != null && title != null) out.add(new FeedItem(url, title, isoDate(date)));
        }
        return out;
    }

    private static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList all = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < all.getLength(); i++) out.add((Element) all.item(i));
        return out;
    }

    /** Text of the first direct child with this local name, stripped; null when absent or blank. */
    private static String text(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                String t = e.getTextContent();
                return t == null || t.isBlank() ? null : t.strip();
            }
        }
        return null;
    }

    private static String atomLink(Element entry) {
        for (Node n = entry.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && "link".equals(e.getLocalName())) {
                String rel = e.getAttribute("rel");
                if ((rel.isEmpty() || rel.equals("alternate")) && !e.getAttribute("href").isBlank()) return e.getAttribute("href").strip();
            }
        }
        return null;
    }

    private static String rfc1123Date(String s) {
        if (s == null) return null;
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String isoDate(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
