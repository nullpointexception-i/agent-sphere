package com.buukle.agent.capability.builtin.tool.webread.util;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * HTML 到 Markdown 转换工具类
 */
@Slf4j
@Component
public class HtmlToMarkdownConverter {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final int MAX_HEADING_LEVEL = 6;

    /**
     * 将 HTML 转换为 Markdown
     *
     * @param html 清理后的 HTML 内容
     * @return Markdown 格式的文本
     */
    public String convert(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }

        try {
            Document doc = Jsoup.parse(html);
            StringBuilder markdown = new StringBuilder();
            convertNode(doc.body(), markdown, new Context());
            return normalizeMarkdown(markdown.toString());
        } catch (Exception e) {
            log.warn("HTML to Markdown conversion failed", e);
            return extractPlainText(html);
        }
    }

    /**
     * 递归转换 DOM 节点
     */
    private void convertNode(Node node, StringBuilder output, Context ctx) {
        if (node == null) {
            return;
        }

        if (node instanceof TextNode) {
            String text = ((TextNode) node).getWholeText().trim();
            if (!text.isEmpty()) {
                output.append(text).append(" ");
            }
        } else if (node instanceof Element) {
            Element element = (Element) node;
            String tagName = element.tagName().toLowerCase();
            convertElement(element, tagName, output, ctx);
        }
    }

    /**
     * 转换 HTML 元素
     */
    private void convertElement(Element element, String tagName, StringBuilder output, Context ctx) {
        switch (tagName) {
            case "h1": appendHeading(1, element, output, ctx); break;
            case "h2": appendHeading(2, element, output, ctx); break;
            case "h3": appendHeading(3, element, output, ctx); break;
            case "h4": appendHeading(4, element, output, ctx); break;
            case "h5": appendHeading(5, element, output, ctx); break;
            case "h6": appendHeading(6, element, output, ctx); break;
            case "p": appendParagraph(element, output, ctx); break;
            case "br": output.append("\n"); break;
            case "a": appendLink(element, output, ctx); break;
            case "strong":
            case "b": appendStrong(element, output, ctx); break;
            case "em":
            case "i": appendEmphasis(element, output, ctx); break;
            case "ul":
            case "ol": appendList(element, tagName, output, ctx); break;
            case "li": appendListItem(element, output, ctx); break;
            case "code": appendCode(element, output, ctx); break;
            case "pre": appendPreformatted(element, output, ctx); break;
            case "blockquote": appendBlockquote(element, output, ctx); break;
            case "img": appendImage(element, output, ctx); break;
            case "hr": output.append("\n---\n"); break;
            case "div":
            case "section":
            case "article":
            case "main":
                appendChildren(element, output, ctx);
                break;
            default:
                appendChildren(element, output, ctx);
        }
    }

    private void appendHeading(int level, Element element, StringBuilder output, Context ctx) {
        ensureNewline(output);
        output.append("#".repeat(Math.min(level, MAX_HEADING_LEVEL))).append(" ");
        appendChildren(element, output, ctx);
        output.append("\n");
    }

    private void appendParagraph(Element element, StringBuilder output, Context ctx) {
        ensureNewline(output);
        appendChildren(element, output, ctx);
        output.append("\n\n");
    }

    private void appendLink(Element element, StringBuilder output, Context ctx) {
        String href = element.attr("href");
        String text = element.text();
        if (href != null && !href.isEmpty() && !text.isEmpty()) {
            output.append("[").append(text).append("](").append(href).append(")");
        } else {
            appendChildren(element, output, ctx);
        }
    }

    private void appendStrong(Element element, StringBuilder output, Context ctx) {
        output.append("**");
        appendChildren(element, output, ctx);
        output.append("**");
    }

    private void appendEmphasis(Element element, StringBuilder output, Context ctx) {
        output.append("*");
        appendChildren(element, output, ctx);
        output.append("*");
    }

    private void appendList(Element element, String tagName, StringBuilder output, Context ctx) {
        ensureNewline(output);
        ctx.listLevel++;
        ctx.isList = true;
        
        for (Element li : element.select("> li")) {
            for (int i = 0; i < ctx.listLevel; i++) {
                output.append("  ");
            }
            if ("ol".equals(tagName)) {
                output.append("1. ");
            } else {
                output.append("- ");
            }
            appendChildren(li, output, ctx);
            output.append("\n");
        }
        
        ctx.listLevel--;
        output.append("\n");
    }

    private void appendListItem(Element element, StringBuilder output, Context ctx) {
        appendChildren(element, output, ctx);
    }

    private void appendCode(Element element, StringBuilder output, Context ctx) {
        output.append("`").append(element.text()).append("`");
    }

    private void appendPreformatted(Element element, StringBuilder output, Context ctx) {
        ensureNewline(output);
        output.append("```\n");
        output.append(element.text());
        output.append("\n```\n");
    }

    private void appendBlockquote(Element element, StringBuilder output, Context ctx) {
        ensureNewline(output);
        String[] lines = element.text().split("\n");
        for (String line : lines) {
            output.append("> ").append(line).append("\n");
        }
        output.append("\n");
    }

    private void appendImage(Element element, StringBuilder output, Context ctx) {
        String src = element.attr("src");
        String alt = element.attr("alt");
        if (src != null && !src.isEmpty()) {
            output.append("![").append(alt != null ? alt : "").append("](").append(src).append(")");
        }
    }

    private void appendChildren(Element element, StringBuilder output, Context ctx) {
        for (Node child : element.childNodes()) {
            convertNode(child, output, ctx);
        }
    }

    private void ensureNewline(StringBuilder output) {
        if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
            output.append("\n");
        }
    }

    /**
     * 规范化 Markdown - 移除过多的空行和空白
     */
    private String normalizeMarkdown(String markdown) {
        // 移除多个连续空行
        markdown = markdown.replaceAll("\\n{3,}", "\n\n");
        // 移除行尾空白
        markdown = markdown.replaceAll(" +\n", "\n");
        // 规范化空白
        markdown = WHITESPACE_PATTERN.matcher(markdown).replaceAll(" ");
        return markdown.trim();
    }

    /**
     * 提取纯文本（备选方案）
     */
    private String extractPlainText(String html) {
        try {
            Document doc = Jsoup.parse(html);
            return doc.text();
        } catch (Exception e) {
            log.warn("Plain text extraction failed", e);
            return "";
        }
    }

    /**
     * 转换上下文
     */
    private static class Context {
        int listLevel = 0;
        boolean isList = false;
    }
}
