package com.buukle.agent.bootstrap.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the backend against raw status strings and untyped payload maps.
 *
 * <p>Status columns must use the constants under {@code dtvo.enums}; outbox and event
 * payloads must use typed VOs. Both regressions compile cleanly, so only a source scan
 * catches them. The scan covers every {@code src/main/java} tree of the backend, so new
 * modules are protected automatically.
 */
class StatusMagicValueScanTest {

    private static final Pattern STATUS_SETTER = Pattern.compile(
            "\\b(setStatus|setState|setDecision|setGroupDecision|setMode)\\(\\s*\"");
    private static final Pattern STATUS_QUERY = Pattern.compile(
            "::get(Status|State|Decision|GroupDecision|Mode),\\s*\"");
    private static final Pattern PAYLOAD_MAP = Pattern.compile(
            "Map\\.of\\(\\s*\"(text|format|cardType|status|summary|workItemId|superAgentId"
                    + "|targetMessageId|replyToId|errorMessage)\"");
    private static final Pattern PAYLOAD_PUT = Pattern.compile(
            "\\.put\\(\\s*\"(text|format|cardType|status|summary|workItemId|superAgentId"
                    + "|targetMessageId|replyToId|errorMessage)\"");

    @Test
    @DisplayName("Backend production code uses status constants and typed payload VOs")
    void noRawStatusOrPayloadMap() throws IOException {
        Path repoRoot = findRepoRoot();
        assertNotNull(repoRoot, "Cannot locate backend source root from "
                + Paths.get("").toAbsolutePath());

        List<Path> javaRoots = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(repoRoot)) {
            tree.filter(Files::isDirectory)
                    .filter(path -> path.toString().replace('\\', '/').endsWith("/src/main/java"))
                    .filter(path -> !path.toString().contains("target"))
                    .forEach(javaRoots::add);
        }
        assertTrue(javaRoots.size() >= 20,
                "Only " + javaRoots.size() + " source roots found under " + repoRoot
                        + "; the scan path is probably wrong");

        List<String> violations = new ArrayList<>();
        for (Path javaRoot : javaRoots) {
            try (Stream<Path> tree = Files.walk(javaRoot)) {
                tree.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.toString().contains("target"))
                        .forEach(path -> inspect(repoRoot, path, violations));
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "Raw status strings or untyped payload maps found:\n  "
                        + String.join("\n  ", violations));
    }

    private void inspect(Path repoRoot, Path file, List<String> violations) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String relative = repoRoot.relativize(file).toString().replace('\\', '/');
            int line = 0;
            for (String text : source.split("\\R")) {
                line++;
                if (STATUS_SETTER.matcher(text).find()
                        || STATUS_QUERY.matcher(text).find()
                        || PAYLOAD_MAP.matcher(text).find()
                        || PAYLOAD_PUT.matcher(text).find()) {
                    violations.add(relative + ":" + line + " -> " + text.strip());
                }
            }
        } catch (IOException e) {
            violations.add(file + " -> " + e.getMessage());
        }
    }

    private Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("agent-sphere-bootstrap"))
                    && Files.isDirectory(current.resolve("agent-sphere-common"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
