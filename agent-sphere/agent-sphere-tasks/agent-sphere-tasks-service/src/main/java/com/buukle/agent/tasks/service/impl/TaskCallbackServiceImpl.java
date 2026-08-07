package com.buukle.agent.tasks.service.impl;

import com.buukle.agent.tasks.domain.AgentTask;
import com.buukle.agent.tasks.dtvo.TaskCallbackBody;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
public class TaskCallbackServiceImpl implements TaskCallbackService {

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Value("${hri-ai.tasks.callback.max-attempts:3}")
    private int maxAttempts;

    @Value("${hri-ai.tasks.callback.initial-delay:2s}")
    private Duration initialDelay;

    @Override
    public void notifyTerminal(AgentTask task) {
        if (task == null || task.getCallbackUrl() == null || task.getCallbackUrl().isBlank()) {
            return;
        }
        TaskCallbackBody body = new TaskCallbackBody();
        body.setAsTaskId(task.getId());
        body.setStatus(task.getStatus());
        body.setResultJson(task.getResultJson() == null ? "" : task.getResultJson());
        body.setRemark(task.getRemark() == null ? "" : task.getRemark());
        Thread.ofVirtual().start(() -> postWithRetry(task, JsonUtils.toJson(body)));
    }

    private void postWithRetry(AgentTask task, String body) {
        long delayMillis = initialDelay.toMillis();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(task.getCallbackUrl()))
                        .timeout(REQUEST_TIMEOUT)
                        .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (isSuccessStatusCode(response.statusCode())) {
                    log.info("Task {} callback success: status={}, url={}",
                            task.getId(), task.getStatus(), task.getCallbackUrl());
                    return;
                }
                log.warn("Task {} callback http {}: url={}", task.getId(), response.statusCode(), task.getCallbackUrl());
            } catch (Exception e) {
                log.warn("Task {} callback failed (attempt {}/{}): {}", task.getId(), attempt, maxAttempts, e.getMessage());
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delayMillis *= 2;
            }
        }
    }

    private boolean isSuccessStatusCode(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}
