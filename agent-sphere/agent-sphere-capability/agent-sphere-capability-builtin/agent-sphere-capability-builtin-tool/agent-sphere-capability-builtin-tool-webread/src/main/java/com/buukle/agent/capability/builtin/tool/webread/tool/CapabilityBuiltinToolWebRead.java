package com.buukle.agent.capability.builtin.tool.webread.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.capability.builtin.tool.webfetch.dtvo.dto.WebFetchExecuteContext;
import com.buukle.agent.capability.builtin.tool.webfetch.dtvo.vo.WebFetchResultVO;
import com.buukle.agent.capability.builtin.tool.webfetch.tool.CapabilityBuiltinToolWebFetch;
import com.buukle.agent.capability.builtin.tool.webread.dtvo.dto.WebReadExecuteContext;
import com.buukle.agent.capability.builtin.tool.webread.dtvo.vo.WebReadResultVO;
import com.buukle.agent.capability.builtin.tool.webread.util.*;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class CapabilityBuiltinToolWebRead implements CapabilityBuiltinToolSpi {

    private final AgentRuntimeProperties properties;
    private final HtmlCleaner htmlCleaner;
    private final HtmlToMarkdownConverter markdownConverter;
    private final TitleExtractor titleExtractor;
    private final HtmlValidationUtils validationUtils;
    private final JinaReader jinaReader;
    private CapabilityBuiltinToolWebFetch webFetch;

    public CapabilityBuiltinToolWebRead(
            AgentRuntimeProperties properties,
            HtmlCleaner htmlCleaner,
            HtmlToMarkdownConverter markdownConverter,
            TitleExtractor titleExtractor,
            HtmlValidationUtils validationUtils,
            JinaReader jinaReader) {
        this.properties = properties;
        this.htmlCleaner = htmlCleaner;
        this.markdownConverter = markdownConverter;
        this.titleExtractor = titleExtractor;
        this.validationUtils = validationUtils;
        this.jinaReader = jinaReader;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWebFetch(CapabilityBuiltinToolWebFetch webFetch) {
        this.webFetch = webFetch;
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.WEB_READ;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolWebRead.class.getSimpleName());
        info.setDescription("Read web page content and extract as clean markdown");
        info.setDisplayNameCn("网页读取");
        info.setDisplayNameEn("Web Read");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(WebReadExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(WebReadResultVO.class));
        return info;
    }

    @Override
    public boolean needConfig() {
        return true;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return WebReadExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return WebReadResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        WebReadExecuteContext wrCtx = (WebReadExecuteContext) ctx;
        return readUrl(wrCtx.getUrl(), wrCtx.getTimeoutSeconds());
    }

    /**
     * 读取 URL 并转换为 Markdown
     * Primary: 使用 Jina API（如果启用）
     * Fallback: 使用内部 WebFetch + 转换
     */
    public WebReadResultVO readUrl(String url, int timeout) {
        int effectiveTimeout = timeout > 0 ? timeout : (int) properties.getTool().getWebRead().getReadTimeout().getSeconds();

        // Primary: Jina API
        if (properties.getTool().getWebReadAdvanced().isUseJinaFallback()) {
            try {
                WebReadResultVO jinaResult = jinaReader.readUrl(url, effectiveTimeout);
                if (jinaResult.getStatusCode() == 200 && jinaResult.getMarkdown() != null
                        && !jinaResult.getMarkdown().isEmpty()) {
                    return jinaResult;
                }
                log.warn("Jina returned empty result for url: {}, falling back to internal", url);
            } catch (Exception e) {
                log.warn("Jina API failed for url: {}, falling back to internal", url, e);
            }
        }

        // Fallback: WebFetch + 内部转换
        try {
            return readUrlInternal(url, effectiveTimeout);
        } catch (Exception e) {
            log.error("Internal conversion also failed for url: {}", url, e);
            return new WebReadResultVO(500, "", "",
                    "All methods failed: " + e.getMessage());
        }
    }

    /**
     * 使用内部方法将 HTML 转换为 Markdown
     */
    private WebReadResultVO readUrlInternal(String url, int timeout) throws Exception {
        // 1. 通过 webFetch 获取 HTML（带重试）
        WebFetchResultVO fetchResult = fetchHtml(url, timeout);

        int statusCode = fetchResult.getStatusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("Failed to fetch URL: HTTP " + statusCode);
        }

        String html = fetchResult.getContent();
        if (html == null || html.isEmpty()) {
            throw new IOException("Fetched content is empty");
        }

        // 2. 验证 HTML 内容
        long maxContentLength = properties.getTool().getWebReadAdvanced().getHtmlToMarkdown().getMaxContentLength();
        if (!validationUtils.isValid(html, maxContentLength)) {
            throw new IOException("HTML content validation failed");
        }

        // 3. 清理 HTML
        String excludeTags = properties.getTool().getWebReadAdvanced().getHtmlToMarkdown().getExcludeTags();
        String cleanedHtml = htmlCleaner.clean(html, excludeTags);

        if (cleanedHtml == null || cleanedHtml.isEmpty()) {
            throw new IOException("HTML cleaning resulted in empty content");
        }

        // 4. 转换为 Markdown
        String markdown = markdownConverter.convert(cleanedHtml);

        if (markdown == null || markdown.isEmpty()) {
            throw new IOException("Markdown conversion resulted in empty content");
        }

        // 5. 提取标题
        String title = titleExtractor.extract(html);

        log.debug("Successfully converted URL to markdown: {} (size: {} bytes)", url, markdown.length());
        return new WebReadResultVO(200, markdown, title, null);
    }

    /**
     * 通过 webFetch 获取 HTML 内容
     */
    private WebFetchResultVO fetchHtml(String url, int timeout) throws Exception {
        if (webFetch == null) {
            throw new IllegalStateException("WebFetch service not available");
        }

        WebFetchExecuteContext ctx = new WebFetchExecuteContext();
        ctx.setUrl(url);
        ctx.setTimeoutSeconds(timeout);

        return (WebFetchResultVO) webFetch.execute(ctx);
    }
}
