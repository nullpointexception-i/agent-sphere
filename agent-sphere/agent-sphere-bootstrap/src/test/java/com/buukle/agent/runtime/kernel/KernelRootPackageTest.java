package com.buukle.agent.runtime.kernel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The kernel root is a facade package: only {@link SessionRunCoordinator} may live directly in it.
 */
class KernelRootPackageTest {

    @Test
    void kernelRootContainsOnlySessionRunCoordinator() throws IOException {
        Path kernelRoot = locateKernelRoot();
        try (var files = Files.list(kernelRoot)) {
            List<String> javaFiles = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            assertEquals(List.of("SessionRunCoordinator.java"), javaFiles);
        }
    }

    private static Path locateKernelRoot() {
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(Path.of("agent-sphere-runtime", "agent-sphere-runtime-kernel",
                    "src", "main", "java", "com", "buukle", "agent", "runtime", "kernel"));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("kernel package not found from " + Paths.get("").toAbsolutePath());
    }
}
