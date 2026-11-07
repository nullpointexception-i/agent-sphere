package com.buukle.agent.capability.builtin.tool.webread.util;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * HTML cleaning utility - removes unnecessary tags and content
 * <p>
 * Uses a layered strategy:
 * Layer one: unconditionally remove absolute-noise tags (script, style, noscript, meta, link, iframe, embed, object)
 * Layer two: try removing structural tags (header, footer, nav), but fall back to the layer-one result if that empties the content
 */
@Slf4j
@Component
public class HtmlCleaner {

    private static final Set<String> ABSOLUTE_NOISE = new HashSet<>(Arrays.asList(
            "script", "style", "noscript", "meta", "link",
            "iframe", "embed", "object"
    ));

    private static final Set<String> STRUCTURAL_TAGS = new HashSet<>(Arrays.asList(
            "header", "footer", "nav"
    ));

    /**
     * Cleans HTML - removes the given tags and script content
     *
     * @param html        original HTML content
     * @param excludeTags comma-separated list of tags to exclude
     * @return cleaned HTML
     */
    public String clean(String html, String excludeTags) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        try {
            Document doc = Jsoup.parse(html);

            // Layer one: unconditionally remove absolute-noise tags
            removeElements(doc.body(), ABSOLUTE_NOISE);
            String firstPass = doc.body().html();

            // Layer two: try removing structural tags, but never empty the content
            Set<String> structuralSet = new HashSet<>(STRUCTURAL_TAGS);
            if (excludeTags != null && !excludeTags.trim().isEmpty()) {
                String[] parts = excludeTags.split(",");
                for (String tag : parts) {
                    structuralSet.add(tag.trim().toLowerCase());
                }
            }
            structuralSet.removeAll(ABSOLUTE_NOISE);

            Document strippedDoc = Jsoup.parse(firstPass);
            removeElements(strippedDoc.body(), structuralSet);
            cleanWhitespace(strippedDoc.body());
            String secondPass = strippedDoc.body().html();

            // Use the layer-two result if it is not empty after cleaning
            if (secondPass != null && !secondPass.trim().isEmpty()) {
                return secondPass;
            }

            // Fall back to the layer-one result
            cleanWhitespace(doc.body());
            return doc.body().html();
        } catch (Exception e) {
            log.warn("HTML cleaning failed, returning original content", e);
            return html;
        }
    }

    /**
     * Cleans HTML using the default tag list
     */
    public String clean(String html) {
        return clean(html, "");
    }

    /**
     * Recursively removes all elements of the given tags
     */
    private void removeElements(Element element, Set<String> tagsToRemove) {
        element.select(String.join(",", tagsToRemove)).forEach(Element::remove);
    }

    /**
     * Cleans whitespace-only text nodes
     */
    private void cleanWhitespace(Element element) {
        var childNodes = element.childNodes();
        for (int i = childNodes.size() - 1; i >= 0; i--) {
            Node node = childNodes.get(i);
            if (node instanceof TextNode) {
                String text = ((TextNode) node).getWholeText();
                if (text != null && text.trim().isEmpty()) {
                    node.remove();
                }
            }
        }
    }
}
