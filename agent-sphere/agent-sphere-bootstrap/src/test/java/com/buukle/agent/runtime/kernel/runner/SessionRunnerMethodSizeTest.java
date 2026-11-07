package com.buukle.agent.runtime.kernel.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the run-loop refactor: no method under {@code runner/**} may exceed 200 physical lines.
 */
class SessionRunnerMethodSizeTest {

    private static final int MAX_METHOD_LINES = 200;

    @Test
    void runnerMethodsStayBelowTwoHundredLines() throws IOException {
        Path runnerRoot = locateRunnerRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(runnerRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                collectViolations(file, violations);
            }
        }
        assertTrue(violations.isEmpty(),
                "Runner methods must stay <= " + MAX_METHOD_LINES + " lines: " + violations);
    }

    private void collectViolations(Path source, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!looksLikeMethodStart(line)) {
                continue;
            }
            int depth = 0;
            int end = i;
            boolean closed = false;
            for (int j = i; j < lines.size(); j++) {
                depth += count(lines.get(j), '{');
                depth -= count(lines.get(j), '}');
                if (depth <= 0) {
                    end = j;
                    closed = true;
                    break;
                }
            }
            if (!closed) {
                continue;
            }
            int length = end - i + 1;
            if (length > MAX_METHOD_LINES) {
                violations.add(source.getFileName() + ":"
                        + line.substring(0, Math.min(80, line.length())) + " -> " + length + " lines");
            }
        }
    }

    private static boolean looksLikeMethodStart(String line) {
        if (!line.endsWith("{") || !line.contains("(")) {
            return false;
        }
        if (line.startsWith("if ") || line.startsWith("for ") || line.startsWith("while ")
                || line.startsWith("switch ") || line.startsWith("catch ") || line.startsWith("try")
                || line.startsWith("return ") || line.startsWith("new ")) {
            return false;
        }
        return line.startsWith("public ") || line.startsWith("private ")
                || line.startsWith("protected ") || line.startsWith("static ")
                || line.startsWith("final ");
    }

    private static int count(String text, char needle) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == needle) {
                total++;
            }
        }
        return total;
    }

    private static Path locateRunnerRoot() {
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(Path.of("agent-sphere-runtime", "agent-sphere-runtime-kernel",
                    "src", "main", "java", "com", "buukle", "agent", "runtime", "kernel", "runner"));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("runner package not found from " + Paths.get("").toAbsolutePath());
    }
}
