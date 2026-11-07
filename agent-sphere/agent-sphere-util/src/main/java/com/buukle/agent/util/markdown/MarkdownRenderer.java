package com.buukle.agent.util.markdown;

import java.util.List;
import java.util.Map;

/**
 * Renders structured configuration as Markdown for task prompts.
 *
 * <p>Why not dump JSON directly: config carries <b>instructions the model must execute</b>, not
 * data. JSON quotes and braces make the model read it as data, and long rules wrapped in quotes are
 * also harder to follow as instructions; Markdown headings make it obvious at a glance which parts
 * are steps and which are hard constraints.
 *
 * <p>Two rendering modes:
 * <ol>
 *   <li><b>Structured sections (agreed shape)</b>: {@code {"sections":[{"title":"…","body":"…"}, …]}}.
 *       Titles are supplied by the caller (only the business domain knows that {@code hardGates}
 *       should be called "hard gates"); each is rendered as {@code ### title} + body, and the
 *       {@code sections} wrapper heading is <b>not</b> emitted.</li>
 *   <li><b>Generic structure</b>: any other Map/List/scalar, rendered level by level as headings
 *       and list items, so other callers still get readable text even when they do not follow the
 *       section convention.</li>
 * </ol>
 *
 * <p>Two deliberate trade-offs:
 * <ul>
 *   <li>Blank sections (null / whitespace) are skipped entirely — otherwise noise such as
 *       {@code hardGates: null} would be rendered;</li>
 *   <li><b>No code-block wrapping</b> — wrapping in ``` tells the model "this is data", the
 *       opposite of "these are instructions".</li>
 * </ul>
 */
public final class MarkdownRenderer {

    /** Key name of the section convention. */
    private static final String SECTION_KEY = "sections";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_BODY = "body";

    /** Top-level heading level: start at ### to leave room above (callers may have ## headings). */
    private static final int BASE_HEADING_LEVEL = 3;
    private static final int MAX_HEADING_LEVEL = 6;
    private static final char HEADING_MARK = '#';
    private static final String LIST_ITEM_PREFIX = "- ";
    private static final String LINE_SEPARATOR = "\n";

    private MarkdownRenderer() {
    }

    /**
     * Renders a task config. Expands sections when the section convention is followed, otherwise
     * falls back to generic rendering. The result has no leading or trailing blank lines (the caller
     * decides where to splice it in).
     */
    public static String renderConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "";
        }
        if (config.size() == 1) {
            Object sections = config.get(SECTION_KEY);
            if (sections instanceof List<?> list && isSectionList(list)) {
                return renderSections(list);
            }
        }
        return renderValue(config, BASE_HEADING_LEVEL);
    }

    /** Renders each section as {@code ### title\nbody}; title and body are trimmed, empty sections are skipped. */
    private static String renderSections(List<?> sections) {
        StringBuilder sb = new StringBuilder();
        for (Object item : sections) {
            if (!(item instanceof Map<?, ?> section)) {
                continue;
            }
            String title = asText(section.get(FIELD_TITLE));
            Object body = section.get(FIELD_BODY);
            if (title == null || isBlankValue(body)) {
                continue;
            }
            appendBlock(sb, heading(BASE_HEADING_LEVEL) + title);
            appendBlock(sb, renderValue(body, BASE_HEADING_LEVEL + 1));
        }
        return sb.toString();
    }

    /** Generic rendering: Map entries become headings, Lists become list items, scalars are kept as-is. */
    private static String renderValue(Object value, int level) {
        if (isBlankValue(value)) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object entryValue = entry.getValue();
                if (isBlankValue(entryValue)) {
                    continue;
                }
                String key = asText(entry.getKey());
                if (key == null) {
                    continue;
                }
                appendBlock(sb, heading(level) + key);
                appendBlock(sb, renderValue(entryValue, level + 1));
            }
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (isBlankValue(item)) {
                    continue;
                }
                appendBlock(sb, LIST_ITEM_PREFIX + asText(item));
            }
            return sb.toString();
        }
        return asText(value);
    }

    /** Appends a non-blank block (exactly one newline between blocks). */
    private static void appendBlock(StringBuilder sb, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(LINE_SEPARATOR);
        }
        sb.append(block.strip());
    }

    private static String heading(int level) {
        int safeLevel = Math.min(Math.max(level, 1), MAX_HEADING_LEVEL);
        return String.valueOf(HEADING_MARK).repeat(safeLevel) + " ";
    }

    /** Section-convention check: non-empty, and every element is a Map with a non-blank title. */
    private static boolean isSectionList(List<?> list) {
        if (list.isEmpty()) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || asText(map.get(FIELD_TITLE)) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
