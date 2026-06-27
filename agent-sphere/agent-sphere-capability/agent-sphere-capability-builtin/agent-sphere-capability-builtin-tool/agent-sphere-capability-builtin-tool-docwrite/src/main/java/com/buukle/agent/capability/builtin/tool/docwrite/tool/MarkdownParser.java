package com.buukle.agent.capability.builtin.tool.docwrite.tool;

import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo.HeadingInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private static final Pattern HEADING_LINE_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    public static List<HeadingInfo> parseHeadings(String markdown) {
        List<HeadingInfo> result = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) return result;
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING_PATTERN.matcher(lines[i]);
            if (m.matches()) {
                result.add(new HeadingInfo(i + 1, m.group(1).length(), m.group(2).trim()));
            }
        }
        return result;
    }

    public static String extractSection(String markdown, String headingSearch) {
        if (markdown == null || markdown.isEmpty()) return null;
        String[] lines = markdown.split("\n", -1);

        String search = headingSearch;
        if (search.startsWith("#")) {
            Matcher m = HEADING_PATTERN.matcher(search);
            if (m.matches()) {
                search = m.group(2).trim();
            }
        }

        int startLine = -1;
        int startLevel = -1;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING_PATTERN.matcher(lines[i]);
            if (m.matches()) {
                String text = m.group(2);
                if (headingMatches(text, search)) {
                    startLine = i;
                    startLevel = m.group(1).length();
                    break;
                }
            }
        }

        if (startLine == -1) return null;

        int endLine = lines.length;
        for (int i = startLine + 1; i < lines.length; i++) {
            Matcher m = HEADING_PATTERN.matcher(lines[i]);
            if (m.matches() && m.group(1).length() <= startLevel) {
                endLine = i;
                break;
            }
        }

        return String.join("\n", Arrays.copyOfRange(lines, startLine, endLine));
    }

    public static String extractLines(String markdown, int startLine, Integer endLine) {
        if (markdown == null || markdown.isEmpty()) return "";
        String[] lines = markdown.split("\n", -1);

        int startIdx = Math.max(0, startLine - 1);
        int endIdx = endLine != null ? Math.min(lines.length - 1, endLine - 1) : lines.length - 1;

        if (startIdx > endIdx || startIdx >= lines.length) return "";

        return String.join("\n", Arrays.copyOfRange(lines, startIdx, endIdx + 1));
    }

    public static String insertAfterSection(String markdown, String headingSearch, String newContent) {
        if (markdown == null || markdown.isEmpty() || newContent == null) return null;

        String search = headingSearch;
        if (search.startsWith("#")) {
            Matcher m = HEADING_PATTERN.matcher(search);
            if (m.matches()) search = m.group(2).trim();
        }

        Matcher matcher = HEADING_LINE_PATTERN.matcher(markdown);
        Integer matchStart = null;
        int matchLevel = -1;
        Integer insertPoint = null;

        while (matcher.find()) {
            String text = matcher.group(2).trim();
            int level = matcher.group(1).length();

            if (matchStart == null) {
                if (headingMatches(text, search)) {
                    matchStart = matcher.start();
                    matchLevel = level;
                }
            } else {
                if (level <= matchLevel) {
                    insertPoint = matcher.start();
                    break;
                }
            }
        }

        if (matchStart == null) return null;
        if (insertPoint == null) insertPoint = markdown.length();

        return markdown.substring(0, insertPoint) + "\n\n" + newContent + markdown.substring(insertPoint);
    }

    public static String replaceFirst(String markdown, String searchText, String replaceWith) {
        if (markdown == null || markdown.isEmpty() || searchText == null || replaceWith == null) return null;
        int first = markdown.indexOf(searchText);
        if (first == -1) return null;
        int second = markdown.indexOf(searchText, first + searchText.length());
        if (second != -1) return null;

        return markdown.substring(0, first) + replaceWith + markdown.substring(first + searchText.length());
    }

    public static String replaceSection(String fullContent, String oldSection, String newSection) {
        if (fullContent == null || oldSection == null || newSection == null) return null;
        int idx = fullContent.indexOf(oldSection);
        if (idx == -1) return null;
        int second = fullContent.indexOf(oldSection, idx + oldSection.length());
        if (second != -1) return null;
        return fullContent.substring(0, idx) + newSection + fullContent.substring(idx + oldSection.length());
    }

    private static boolean headingMatches(String headingText, String search) {
        String ht = headingText.trim();
        String s = search.trim();
        if (ht.equals(s)) return true;
        if (ht.equalsIgnoreCase(s)) return true;
        return ht.toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT));
    }
}
