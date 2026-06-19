package com.buukle.agent.capability.builtin.tool.webread.util;

import com.buukle.agent.capability.builtin.tool.webread.dtvo.dto.JinaReaderResponseDto;
import com.buukle.agent.capability.builtin.tool.webread.dtvo.vo.WebReadResultVO;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Jina Reader 工具类 - 调用 Jina Reader API 获取 Markdown
 * <p>
 * Get your Jina AI API key for free: https://jina.ai/?sui=apikey
 */
@Slf4j
@Component
public class JinaReader {

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_ENGINE = "X-Engine";
    private static final String HEADER_RETURN_FORMAT = "X-Return-Format";
    private static final String HEADER_TIMEOUT = "X-Timeout";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String APPLICATION_JSON = "application/json";
    private static final String RETURN_FORMAT_MARKDOWN = "markdown";

    private static final String FIELD_URL = "url";

    private static final int STATUS_OK = 200;
    private static final int STATUS_ERROR = 500;

    private static final String MSG_MISSING_DATA = "Jina API response missing data field";
    private static final String MSG_API_STATUS = "Jina API returned status: ";
    private static final String MSG_API_ERROR = "Jina API error: ";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String readerUrl;
    private final String engine;
    private final int readTimeout;
    private final int timeoutBuffer;

    public JinaReader(AgentRuntimeProperties properties) {
        var config = properties.getTool().getWebReadAdvanced();
        this.apiKey = config.getJinaApiKey();
        this.readerUrl = config.getJinaReaderUrl();
        this.engine = config.getJinaEngine();
        this.readTimeout = Math.max(10, config.getJinaReadTimeout());
        this.timeoutBuffer = Math.max(5, config.getJinaTimeoutBuffer());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, config.getJinaConnectTimeout())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 通过 Jina Reader API 读取 URL 内容并转换为 Markdown
     *
     * @param url            要读取的 URL
     * @param timeoutSeconds 超时时间（秒）
     * @return WebReadResultVO
     */
    public WebReadResultVO readUrl(String url, int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = readTimeout;
        }

        try {
            String jsonBody = JsonUtils.toJson(Map.of(FIELD_URL, url));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(readerUrl))
                    .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .header(HEADER_ACCEPT, APPLICATION_JSON)
                    .header(HEADER_ENGINE, engine)
                    .header(HEADER_RETURN_FORMAT, RETURN_FORMAT_MARKDOWN)
                    .header(HEADER_TIMEOUT, String.valueOf(timeoutSeconds))
                    .timeout(Duration.ofSeconds(timeoutSeconds + timeoutBuffer))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != STATUS_OK) {
                return new WebReadResultVO(response.statusCode(), "", "",
                        MSG_API_STATUS + response.statusCode());
            }

            JinaReaderResponseDto dto = JsonUtils.parse(response.body(), JinaReaderResponseDto.class);
            if (dto == null || dto.getData() == null) {
                return new WebReadResultVO(STATUS_ERROR, "", "", MSG_MISSING_DATA);
            }

            String markdown = dto.getData().getContent() != null ? dto.getData().getContent() : "";
            String title = dto.getData().getTitle() != null ? dto.getData().getTitle() : "";

            return new WebReadResultVO(STATUS_OK, markdown, title, null);
        } catch (Exception e) {
            log.warn("Jina API call failed for url: {}", url, e);
            return new WebReadResultVO(STATUS_ERROR, "", "", MSG_API_ERROR + e.getMessage());
        }
    }
}
