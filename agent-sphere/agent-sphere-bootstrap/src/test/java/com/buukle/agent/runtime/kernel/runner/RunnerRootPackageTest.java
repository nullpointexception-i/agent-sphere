package com.buukle.agent.runtime.kernel.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The runner root is a facade package: only {@link SessionRunner} may live directly in it.
 */
class RunnerRootPackageTest {

    @Test
    void runnerRootContainsOnlySessionRunner() throws IOException {
        Path runnerRoot = locateRunnerRoot();
        try (var files = Files.list(runnerRoot)) {
            List<String> javaFiles = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            assertEquals(List.of("SessionRunner.java"), javaFiles);
        }
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
