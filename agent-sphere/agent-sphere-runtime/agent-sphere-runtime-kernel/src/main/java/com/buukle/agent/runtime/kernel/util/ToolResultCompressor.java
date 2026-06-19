package com.buukle.agent.runtime.kernel.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class ToolResultCompressor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DEPTH = 5;
    private static final int ARRAY_EXPAND_THRESHOLD = 5;
    private static final int ARRAY_SHOW_ITEMS = 3;
    private static final int STRING_HEAD_CHARS = 100;
    private static final int STRING_TAIL_CHARS = 50;

    public static String compress(String raw, int maxValueChars) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            Object node = MAPPER.readValue(raw, Object.class);
            Object compressed = jsonCompress(node, 0, maxValueChars);
            String result = MAPPER.writeValueAsString(compressed);
            return result.length() > raw.length() ? raw : result;
        } catch (Exception e) {
            return textTruncate(raw, maxValueChars);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object jsonCompress(Object node, int depth, int maxValueChars) {
        if (depth > MAX_DEPTH) return "[deep nested]";

        if (node instanceof Map map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : (Set<Map.Entry<String, Object>>) map.entrySet()) {
                Object val = jsonCompress(entry.getValue(), depth + 1, maxValueChars);
                if (val == null || (val instanceof String s && s.isEmpty())) continue;
                result.put(entry.getKey(), val);
            }
            return result;
        }

        if (node instanceof List list) {
            if (list.isEmpty()) return list;
            if (list.size() <= ARRAY_EXPAND_THRESHOLD) {
                return list.stream().map(e -> jsonCompress(e, depth + 1, maxValueChars)).toList();
            }
            var head = list.subList(0, ARRAY_SHOW_ITEMS).stream()
                .map(e -> jsonCompress(e, depth + 1, maxValueChars)).toList();
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("_count", list.size());
            arr.put("_showing", ARRAY_SHOW_ITEMS);
            arr.put("items", head);
            return arr;
        }

        if (node instanceof String s) {
            if (s.length() <= maxValueChars) return s;
            int head = Math.min(maxValueChars / 2, STRING_HEAD_CHARS);
            int tail = Math.min(maxValueChars / 3, STRING_TAIL_CHARS);
            return s.substring(0, head)
                + "\n... [+" + (s.length() - head - tail) + " chars] ...\n"
                + s.substring(s.length() - tail);
        }

        return node;
    }

    private static String textTruncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int head = maxChars / 2;
        int tail = maxChars / 3;
        return text.substring(0, head)
            + "\n... [omitted " + (text.length() - head - tail) + " chars] ...\n"
            + text.substring(text.length() - tail);
    }

}
