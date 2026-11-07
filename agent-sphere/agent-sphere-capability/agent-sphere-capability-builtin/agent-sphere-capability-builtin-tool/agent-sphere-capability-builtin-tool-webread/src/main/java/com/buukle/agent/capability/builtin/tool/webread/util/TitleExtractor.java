package com.buukle.agent.capability.builtin.tool.webread.util;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Title extraction utility - extracts a page title from HTML intelligently
 */
@Slf4j
@Component
public class TitleExtractor {

    private static final int MAX_TITLE_LENGTH = 200;

    /**
     * Extracts the title from HTML
     * Priority: <title> > og:title > meta title > first <h1>
     *
     * @param html HTML content
     * @return the extracted title
     */
    public String extract(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        try {
            Document doc = Jsoup.parse(html);

            // 1. Try extracting from the <title> tag
            String title = extractFromTitle(doc);
            if (!title.isEmpty()) {
                return title;
            }

            // 2. Try extracting from the og:title meta tag
            title = extractFromMetaTag(doc, "property", "og:title");
            if (!title.isEmpty()) {
                return title;
            }

            // 3. Try extracting from meta name="title"
            title = extractFromMetaTag(doc, "name", "title");
            if (!title.isEmpty()) {
                return title;
            }

            // 4. Try extracting from the first <h1>
            title = extractFromFirstHeading(doc);
            if (!title.isEmpty()) {
                return title;
            }

            // 5. Try extracting from the first <h2>
            title = extractFromHeading(doc, "h2");
            if (!title.isEmpty()) {
                return title;
            }

            // 6. Return an empty string
            return "";
        } catch (Exception e) {
            log.warn("Title extraction failed", e);
            return "";
        }
    }

    /**
     * Extracts the title from the <title> tag
     */
    private String extractFromTitle(Document doc) {
        Element titleElement = doc.selectFirst("title");
        if (titleElement != null) {
            String title = titleElement.text().trim();
            return normalizeTitleLength(title);
        }
        return "";
    }

    /**
     * Extracts the title from a meta tag
     */
    private String extractFromMetaTag(Document doc, String attrName, String attrValue) {
        Element metaElement = doc.selectFirst(String.format("meta[%s=%s]", attrName, attrValue));
        if (metaElement != null) {
            String title = metaElement.attr("content").trim();
            return normalizeTitleLength(title);
        }
        return "";
    }

    /**
     * Extracts the title from the first <h1> tag
     */
    private String extractFromFirstHeading(Document doc) {
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) {
            String title = h1.text().trim();
            return normalizeTitleLength(title);
        }
        return "";
    }

    /**
     * Extracts the title from the given heading tag
     */
    private String extractFromHeading(Document doc, String selector) {
        Element heading = doc.selectFirst(selector);
        if (heading != null) {
            String title = heading.text().trim();
            return normalizeTitleLength(title);
        }
        return "";
    }

    /**
     * Normalizes the title length
     */
    private String normalizeTitleLength(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }

        // Collapse runs of whitespace
        title = title.replaceAll("\\s+", " ");

        // If it exceeds the maximum length, truncate and append an ellipsis
        if (title.length() > MAX_TITLE_LENGTH) {
            return title.substring(0, MAX_TITLE_LENGTH) + "...";
        }

        return title;
    }
}
