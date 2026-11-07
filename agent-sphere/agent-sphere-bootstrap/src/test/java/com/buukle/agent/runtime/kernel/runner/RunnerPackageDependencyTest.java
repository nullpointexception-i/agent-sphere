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
 * Guard the runner package layering: extracted services must not depend back on the facade.
 */
class RunnerPackageDependencyTest {

    private static final List<String> EXTRACTED_PACKAGES = List.of(
            "loop", "resume", "turn", "tool", "prompt", "compaction", "support");

    @Test
    void extractedRunnerPackagesDoNotImportSessionRunner() throws IOException {
        Path runnerRoot = locateRunnerRoot();
        List<String> violations = new ArrayList<>();
        for (String subPackage : EXTRACTED_PACKAGES) {
            Path dir = runnerRoot.resolve(subPackage);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (source.contains("import com.buukle.agent.runtime.kernel.runner.SessionRunner;")) {
                        violations.add(file.getFileName().toString());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Extracted runner packages must not import SessionRunner: " + violations);
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
