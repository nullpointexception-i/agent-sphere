package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.runtime.kernel.exception.KernelErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CliExecutorService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final int MAX_OUTPUT_BYTES = 100 * 1024;
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");

    private final long defaultTimeoutSeconds;

    public CliExecutorService(AgentRuntimeProperties properties) {
        this.defaultTimeoutSeconds = properties.getTool().getCliTimeout().getSeconds();
    }

    private static final String KEY_EXIT_CODE = "exitCode";
    private static final String KEY_STDOUT = "stdout";
    private static final String KEY_STDERR = "stderr";

    private static final String TRUNCATION_MESSAGE = "\n... [output truncated at 100KB]";
    private static final String TIMEOUT_TEMPLATE = "CLI command timed out after %ds";

    public String execute(Map<String, Object> binding, String argumentsJson) {
        String commandTemplate = getString(binding, "commandTemplate");
        String workingDir = getString(binding, "workingDir");

        Map<String, String> args = parseArguments(argumentsJson);
        String resolvedCommand = renderTemplate(commandTemplate, args);

        log.info("Executing CLI command: workingDir={}, command={}", workingDir, resolvedCommand);

        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(workingDir != null && !workingDir.isBlank()
                ? new java.io.File(workingDir) : null);

            if (IS_WINDOWS) {
                pb.command("cmd.exe", "/c", resolvedCommand);
            } else {
                pb.command("sh", "-c", resolvedCommand);
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(defaultTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException(KernelErrorCode.TOOL_EXECUTION_FAILED,
                    String.format(TIMEOUT_TEMPLATE, defaultTimeoutSeconds));
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int bytes = 0;
                while ((line = reader.readLine()) != null) {
                    bytes += line.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > MAX_OUTPUT_BYTES) {
                        output.append(TRUNCATION_MESSAGE);
                        break;
                    }
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();

            if (exitCode != 0) {
                log.warn("CLI command exited with code {}: {}", exitCode, result);
                return buildResult(exitCode, "", result);
            }

            return buildResult(0, result, "");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("CLI execution failed", e);
            throw new BizException(KernelErrorCode.TOOL_EXECUTION_FAILED, "CLI execution error: " + e.getMessage());
        }
    }

    private Map<String, String> parseArguments(String argumentsJson) {
        Map<String, String> result = new HashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) return result;
        try {
            Map<String, Object> map = JSON.readValue(argumentsJson, MAP_TYPE);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
            }
        } catch (Exception e) {
            log.warn("Failed to parse CLI arguments JSON: {}", argumentsJson, e);
        }
        return result;
    }

    private String renderTemplate(String template, Map<String, String> args) {
        String result = template;
        for (Map.Entry<String, String> entry : args.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    private String buildResult(int exitCode, String stdout, String stderr) {
        return "{" +
            "\"" + KEY_EXIT_CODE + "\":" + exitCode + "," +
            "\"" + KEY_STDOUT + "\":" + toJsonString(stdout) + "," +
            "\"" + KEY_STDERR + "\":" + toJsonString(stderr) +
            "}";
    }

    private static String toJsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
