package com.buukle.agent.capability.builtin.tool.docwrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.dto.DocReadExecuteContext;
import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo.DocWriteResultVO;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.vo.HeadingInfo;
import com.buukle.agent.capability.builtin.tool.spi.util.MarkdownParser;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.DocumentSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityBuiltinToolDocRead implements CapabilityBuiltinToolSpi {

    static final int PREVIEW_LENGTH = 80;
    static final int SUMMARY_PREVIEW_LENGTH = 100;

    static final String ACTION_LIST = "list";
    static final String ACTION_COUNT = "count";
    static final String ACTION_SEARCH_TITLE = "search_by_title";
    static final String ACTION_GET = "get";

    private static final String TOOL_DESCRIPTION = """
            List, count, search, or read a user-visible document. This tool never writes.
            action=list: list all documents in the current workspace (returns id, title, preview per doc)
            action=count: count all documents in the current workspace (returns total count).
              Use this to determine pagination size for list.
            action=search_by_title: search documents by title keyword in the current workspace (requires keyword, returns id, title, preview per matching doc).
            action=get: get content from a specific document (requires documentId).
              Optional modes to control what to fetch:
              - structure=true: return only the outline (headings with line numbers)
              - sectionHeading=text: return the section under the matching heading
              - startLine=n (with optional endLine=m): return specific line range
              Default (no options): return the full document content.
            """;

    private final DocumentSpi documentSpi;
    private final SessionSpi sessionSpi;

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.DOC_READ;
    }

    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolDocRead.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("文档读取");
        info.setDisplayNameEn("Doc Read");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(DocReadExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(DocWriteResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return DocReadExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return DocWriteResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        try {
            DocReadExecuteContext readCtx = (DocReadExecuteContext) ctx;
            String action = readCtx.getAction();
            if (action == null) {
                return result("unknown", "action is required");
            }
            return switch (action) {
                case ACTION_LIST -> handleList(readCtx);
                case ACTION_COUNT -> handleCount(readCtx);
                case ACTION_SEARCH_TITLE -> handleSearchByTitle(readCtx);
                case ACTION_GET -> handleGet(readCtx, readCtx.getDocumentId());
                default -> {
                    log.warn("Unknown docread action: {}", action);
                    yield result(action, "Unknown action. Supported: list, count, search_by_title, get");
                }
            };
        } catch (Exception e) {
            log.warn("docread execute failed action={}", ctx instanceof DocReadExecuteContext d ? d.getAction() : "unknown", e);
            return result("error", "Internal error: " + e.getMessage());
        }
    }

    private DocWriteResultVO handleList(DocReadExecuteContext ctx) {
        Long sessionId = ctx.getSessionId();
        if (sessionId == null) {
            return result(ACTION_LIST, "sessionId is required");
        }
        Long instanceId = resolveInstanceId(sessionId);
        String createdBy = resolveCreator(sessionId);
        List<DocumentVO> docs = documentSpi.listByInstanceAndCreator(
                instanceId, createdBy, ctx.getPage(), ctx.getPageSize());
        List<DocWriteResultVO.DocReadSummaryVO> summaries = docs.stream()
                .map(this::summary)
                .toList();
        DocWriteResultVO r = new DocWriteResultVO();
        r.setAction(ACTION_LIST);
        r.setDocuments(summaries);
        r.setTotal(summaries.size());
        return r;
    }

    private DocWriteResultVO handleCount(DocReadExecuteContext ctx) {
        Long sessionId = ctx.getSessionId();
        if (sessionId == null) {
            return result(ACTION_COUNT, "sessionId is required");
        }
        Long instanceId = resolveInstanceId(sessionId);
        String createdBy = resolveCreator(sessionId);
        long total = documentSpi.countByInstanceAndCreator(instanceId, createdBy);
        DocWriteResultVO r = new DocWriteResultVO();
        r.setAction(ACTION_COUNT);
        r.setTotal((int) total);
        r.setPreview("Total documents: " + total);
        return r;
    }

    private DocWriteResultVO handleSearchByTitle(DocReadExecuteContext ctx) {
        Long sessionId = ctx.getSessionId();
        if (sessionId == null) {
            return result(ACTION_SEARCH_TITLE, "sessionId is required");
        }
        String keyword = ctx.getKeyword();
        if (keyword == null || keyword.isBlank()) {
            return result(ACTION_SEARCH_TITLE, "keyword is required");
        }
        Long instanceId = resolveInstanceId(sessionId);
        String createdBy = resolveCreator(sessionId);
        List<DocumentVO> docs = documentSpi.searchByTitle(
                instanceId, createdBy, keyword, ctx.getPage(), ctx.getPageSize());
        List<DocWriteResultVO.DocReadSummaryVO> summaries = docs.stream()
                .map(this::summary)
                .toList();
        DocWriteResultVO r = new DocWriteResultVO();
        r.setAction(ACTION_SEARCH_TITLE);
        r.setDocuments(summaries);
        r.setTotal(summaries.size());
        r.setPreview("Found " + summaries.size() + " document(s) matching \"" + keyword + "\"");
        return r;
    }

    private DocWriteResultVO handleGet(DocReadExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            return result(ACTION_GET, "documentId is required");
        }
        DocumentVO doc = documentSpi.getById(documentId);
        if (doc == null) {
            return result(ACTION_GET, "Document not found: " + documentId);
        }

        String content = doc.getContent() != null ? doc.getContent() : "";
        String[] lines = content.split("\n", -1);

        if (Boolean.TRUE.equals(ctx.getStructure())) {
            List<HeadingInfo> headings = MarkdownParser.parseHeadings(content);
            DocWriteResultVO r = baseResult(doc);
            r.setHeadings(headings);
            r.setTotalLines(lines.length);
            r.setTotal(headings.size());
            return r;
        }

        if (ctx.getSectionHeading() != null) {
            String section = MarkdownParser.extractSection(content, ctx.getSectionHeading());
            if (section == null) {
                List<HeadingInfo> headings = MarkdownParser.parseHeadings(content);
                DocWriteResultVO r = baseResult(doc);
                r.setPreview("Section not found: \"" + ctx.getSectionHeading() + "\". Available headings:");
                r.setHeadings(headings);
                r.setTotalLines(lines.length);
                r.setTotal(headings.size());
                return r;
            }
            DocWriteResultVO r = baseResult(doc);
            r.setContent(section);
            r.setPreview(preview(section));
            return r;
        }

        if (ctx.getStartLine() != null) {
            String extracted = MarkdownParser.extractLines(content, ctx.getStartLine(), ctx.getEndLine());
            DocWriteResultVO r = baseResult(doc);
            r.setContent(extracted);
            r.setPreview(preview(extracted));
            return r;
        }

        DocWriteResultVO r = baseResult(doc);
        r.setContent(content);
        r.setPreview(preview(content));
        return r;
    }

    private DocWriteResultVO.DocReadSummaryVO summary(DocumentVO doc) {
        String raw = doc.getContent() != null
                ? doc.getContent().replaceAll("[#*`\\n\\r]+", " ").trim()
                : "";
        String preview = raw.length() > SUMMARY_PREVIEW_LENGTH
                ? raw.substring(0, SUMMARY_PREVIEW_LENGTH) + "…"
                : raw;
        return new DocWriteResultVO.DocReadSummaryVO(
                doc.getId(), doc.getTitle(), preview,
                doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
    }

    private static String preview(String content) {
        return content.length() > PREVIEW_LENGTH
                ? content.substring(0, PREVIEW_LENGTH) + "..."
                : content;
    }

    private DocWriteResultVO baseResult(DocumentVO doc) {
        DocWriteResultVO r = new DocWriteResultVO();
        r.setDocumentId(doc.getId());
        r.setTitle(doc.getTitle());
        r.setAction(ACTION_GET);
        return r;
    }

    private static DocWriteResultVO result(String action, String preview) {
        DocWriteResultVO r = new DocWriteResultVO();
        r.setAction(action);
        r.setPreview(preview);
        return r;
    }

    private Long resolveInstanceId(Long sessionId) {
        if (sessionId == null) return 0L;
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            return session != null && session.getAgentInstanceId() != null ? session.getAgentInstanceId() : 0L;
        } catch (Exception e) {
            log.warn("Failed to resolve instanceId from session {}", sessionId, e);
            return 0L;
        }
    }

    private String resolveCreator(Long sessionId) {
        if (sessionId == null) return null;
        try {
            SessionVO session = sessionSpi.getSession(sessionId);
            return session != null ? session.getCreatedBy() : null;
        } catch (Exception e) {
            log.warn("Failed to resolve creator from session {}", sessionId, e);
            return null;
        }
    }
}
