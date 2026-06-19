package com.buukle.agent.capability.builtin.tool.webread.util;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 标题提取工具类 - 从 HTML 中智能提取页面标题
 */
@Slf4j
@Component
public class TitleExtractor {

    private static final int MAX_TITLE_LENGTH = 200;

    /**
     * 从 HTML 中提取标题
     * 优先级：<title> > og:title > meta title > 第一个 <h1>
     *
     * @param html HTML 内容
     * @return 提取出的标题
     */
    public String extract(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        try {
            Document doc = Jsoup.parse(html);

            // 1. 尝试从 <title> 标签提取
            String title = extractFromTitle(doc);
            if (!title.isEmpty()) {
                return title;
            }

            // 2. 尝试从 og:title meta 标签提取
            title = extractFromMetaTag(doc, "property", "og:title");
            if (!title.isEmpty()) {
                return title;
            }

            // 3. 尝试从 meta name="title" 提取
            title = extractFromMetaTag(doc, "name", "title");
            if (!title.isEmpty()) {
                return title;
            }

            // 4. 尝试从第一个 <h1> 提取
            title = extractFromFirstHeading(doc);
            if (!title.isEmpty()) {
                return title;
            }

            // 5. 尝试从第一个 <h2> 提取
            title = extractFromHeading(doc, "h2");
            if (!title.isEmpty()) {
                return title;
            }

            // 6. 返回空字符串
            return "";
        } catch (Exception e) {
            log.warn("Title extraction failed", e);
            return "";
        }
    }

    /**
     * 从 <title> 标签提取标题
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
     * 从 meta 标签提取标题
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
     * 从第一个 <h1> 标签提取标题
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
     * 从指定 heading 标签提取标题
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
     * 规范化标题长度
     */
    private String normalizeTitleLength(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }

        // 移除多个连续空白
        title = title.replaceAll("\\s+", " ");

        // 如果超过最大长度，截断并添加省略号
        if (title.length() > MAX_TITLE_LENGTH) {
            return title.substring(0, MAX_TITLE_LENGTH) + "...";
        }

        return title;
    }
}
