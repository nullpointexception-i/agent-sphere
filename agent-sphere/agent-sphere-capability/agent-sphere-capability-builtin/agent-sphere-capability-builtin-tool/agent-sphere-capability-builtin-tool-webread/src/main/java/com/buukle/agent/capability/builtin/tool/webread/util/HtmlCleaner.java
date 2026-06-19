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
 * HTML 清理工具类 - 移除不必要的标签和内容
 * <p>
 * 采用分层清理策略：
 * 层级一：无条件移除绝对噪声标签（script, style, noscript, meta, link, iframe, embed, object）
 * 层级二：尝试移除结构标签（header, footer, nav），但若清空内容则回退到层级一结果
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
     * 清理 HTML - 移除指定的标签和脚本内容
     *
     * @param html        原始 HTML 内容
     * @param excludeTags 要排除的标签列表（逗号分隔）
     * @return 清理后的 HTML
     */
    public String clean(String html, String excludeTags) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        try {
            Document doc = Jsoup.parse(html);

            // 层级一：无条件移除绝对噪声标签
            removeElements(doc.body(), ABSOLUTE_NOISE);
            String firstPass = doc.body().html();

            // 层级二：尝试移除结构标签，但不允许把内容清空
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

            // 如果层级二清理后不为空，使用层级二结果
            if (secondPass != null && !secondPass.trim().isEmpty()) {
                return secondPass;
            }

            // 回退到层级一结果
            cleanWhitespace(doc.body());
            return doc.body().html();
        } catch (Exception e) {
            log.warn("HTML cleaning failed, returning original content", e);
            return html;
        }
    }

    /**
     * 使用默认标签列表清理 HTML
     */
    public String clean(String html) {
        return clean(html, "");
    }

    /**
     * 递归移除指定标签的所有元素
     */
    private void removeElements(Element element, Set<String> tagsToRemove) {
        element.select(String.join(",", tagsToRemove)).forEach(Element::remove);
    }

    /**
     * 清理空白文本节点
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
