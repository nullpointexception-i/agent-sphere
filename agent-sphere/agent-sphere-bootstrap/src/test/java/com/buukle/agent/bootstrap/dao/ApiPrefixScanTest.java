package com.buukle.agent.bootstrap.dao;

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
 * Guard: main Java code must compose API paths from {@code ApiPrefix}/{@code ApiPaths} instead
 * of hard-coding {@code /api/v1}.
 */
class ApiPrefixScanTest {

    private static final String RAW_PREFIX = "\"/api/v1";

    @Test
    void mainJavaDoesNotHardcodeApiPrefix() throws IOException {
        Path repoRoot = findRepoRoot();
        assertTrue(repoRoot != null, "could not find repository root");

        List<String> violations = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> roots = Files.walk(repoRoot)) {
            roots.filter(Files::isDirectory)
                    .filter(p -> p.toString().replace('\\', '/').endsWith("src/main/java"))
                    .forEach(root -> collect(root, files));
        }

        for (Path file : files) {
            if (file.getFileName().toString().equals("ApiPrefix.java")) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (source.contains(RAW_PREFIX)) {
                violations.add(repoRoot.relativize(file) + " contains " + RAW_PREFIX);
            }
        }

        assertTrue(violations.isEmpty(),
                () -> "Hard-coded /api/v1 found (use ApiPrefix/ApiPaths):\n  " + String.join("\n  ", violations));
    }

    private void collect(Path dir, List<Path> out) {
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("agent-sphere-common"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
