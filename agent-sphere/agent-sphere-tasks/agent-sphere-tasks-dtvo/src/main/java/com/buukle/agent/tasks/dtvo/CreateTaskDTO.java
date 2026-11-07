package com.buukle.agent.tasks.dtvo;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class CreateTaskDTO implements Serializable {
    @NotBlank(message = "goal can't be blank")
    @Size(min = 1, max = 5000)
    private String goal;
    @JsonDeserialize(using = LenientMapDeserializer.class)
    private Map<String, Object> context;
    @JsonDeserialize(using = LenientMapDeserializer.class)
    private Map<String, Object> expectedOutput;
    @JsonDeserialize(using = LenientMapDeserializer.class)
    private Map<String, Object> config;
    @Size(max = 500)
    private String callbackUrl;
    @Size(max = 64)
    private String code;
    @Size(max = 512)
    private String subject;
    @NotBlank(message = "businessType can't be blank")
    @Size(max = 64)
    private String businessType;

    /**
     * Task-level MCP credential: issued by the caller (Bole) when submitting the task and injected as
     * the {@code X-Task-Mcp-Credential} request header when the Agent executes MCP tools, so the server
     * can identify "which task / which user this call belongs to".
     *
     * <p>It must be a top-level standalone field: {@code config} is serialized wholesale into the prompt
     * (a credential in the prompt means handing it to the model), and {@code context} is returned
     * externally with TaskVO. The credential goes only into Redis on submission and is read by the tool
     * execution chain at runtime; it is neither persisted nor placed in any model-visible text.
     */
    @Size(max = 4096)
    private String mcpCredential;

    /**
     * Task timeout in seconds. If the caller (Bole) specifies it, that value wins; if omitted, the
     * AS-side config provides the fallback.
     *
     * <p>This value determines two things at once: the AS-side task's lifetime cap (timeout marks it
     * FAILED), and the MCP credential validity period the caller issues based on it — the two must
     * match, otherwise the task keeps running while the credential has already expired.
     *
     * <p>Upper bound 4 hours: it determines both credential validity and how long the task holds
     * resources, so it is a security-sensitive parameter; out-of-range values are rejected with 400
     * (GlobalExceptionHandler maps MethodArgumentNotValidException to 400).
     */
    @Min(60)
    @Max(14400)
    private Integer taskTimeoutSeconds;
}
