package com.buukle.agent.capability.builtin.tool.docwrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.dto.DocWriteExecuteContext;
import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo.DocWriteResultVO;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.DocumentSpi;
import com.buukle.agent.instance.spi.SessionSpi;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityBuiltinToolDocOperation implements CapabilityBuiltinToolSpi {

    static final int PREVIEW_LENGTH = 80;

    static final String ACTION_CREATE = "create";
    static final String ACTION_APPEND = "append";
    static final String ACTION_OVERWRITE = "overwrite";
    static final String ACTION_LIST = "list";
    static final String ACTION_GET = "get";

    private static final String TOOL_DESCRIPTION = """
            Create, append to, overwrite, list, or read a user-visible document.
            action=create: create a new document (requires title, content)
            action=append: add content to an existing document (requires documentId, content, optional title)
            action=overwrite: replace the content of an existing document (requires documentId, content, optional title)
            action=list: list all documents in this session (returns id, title, preview per doc)
            action=get: get full content of a specific document (requires documentId)
            """;

    private final DocumentSpi documentSpi;
    private final SessionSpi sessionSpi;

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.DOCWRITE;
    }

    @Override
    public boolean needConfig() {
        return true;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolDocOperation.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("文档操作");
        info.setDisplayNameEn("Doc Operation");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(DocWriteExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(DocWriteResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return DocWriteExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return DocWriteResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        DocWriteExecuteContext dwCtx = (DocWriteExecuteContext) ctx;
        String action = dwCtx.getAction();
        Long documentId = dwCtx.getDocumentId();

        return switch (action) {
            case ACTION_CREATE -> handleCreate(dwCtx);
            case ACTION_APPEND -> handleAppend(dwCtx, documentId);
            case ACTION_OVERWRITE -> handleOverwrite(dwCtx, documentId);
            case ACTION_LIST -> handleList(dwCtx);
            case ACTION_GET -> handleGet(dwCtx, documentId);
            default -> {
                log.warn("Unknown docwrite action: {}", action);
                yield new DocWriteResultVO(null, null, action, null, null, null, null);
            }
        };
    }

    private DocWriteResultVO handleCreate(DocWriteExecuteContext ctx) {
        DocumentVO vo = new DocumentVO();
        vo.setTitle(ctx.getTitle() != null ? ctx.getTitle() : "");
        vo.setContent(ctx.getContent());
        vo.setContentType("markdown");
        Long sessionId = ctx.getSessionId();
        Long instanceId = resolveInstanceId(sessionId);
        vo.setSessionId(sessionId);
        vo.setInstanceId(instanceId);
        vo.setRunId(ctx.getRunId());

        Long newId = documentSpi.create(vo);
        String preview = ctx.getContent().substring(0, Math.min(PREVIEW_LENGTH, ctx.getContent().length()));
        return new DocWriteResultVO(newId, vo.getTitle(), ACTION_CREATE, preview, null, null, null);
    }

    private DocWriteResultVO handleAppend(DocWriteExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            return new DocWriteResultVO(null, null, ACTION_APPEND, "documentId is required for append", null, null, null);
        }
        DocumentVO existing = documentSpi.getById(documentId);
        if (existing == null) {
            return new DocWriteResultVO(null, null, ACTION_APPEND, "Document not found: " + documentId, null, null, null);
        }
        String newContent = existing.getContent() + "\n\n" + ctx.getContent();
        String title = ctx.getTitle() != null && !ctx.getTitle().isBlank() ? ctx.getTitle() : existing.getTitle();
        documentSpi.update(documentId, title, newContent);
        String preview = newContent.substring(0, Math.min(PREVIEW_LENGTH, newContent.length()));
        return new DocWriteResultVO(documentId, title, ACTION_APPEND, preview, null, null, null);
    }

    private DocWriteResultVO handleOverwrite(DocWriteExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            return new DocWriteResultVO(null, null, ACTION_OVERWRITE, "documentId is required for overwrite", null, null, null);
        }
        DocumentVO existing = documentSpi.getById(documentId);
        if (existing == null) {
            return new DocWriteResultVO(null, null, ACTION_OVERWRITE, "Document not found: " + documentId, null, null, null);
        }
        String title = ctx.getTitle() != null && !ctx.getTitle().isBlank() ? ctx.getTitle() : existing.getTitle();
        documentSpi.update(documentId, title, ctx.getContent());
        String preview = ctx.getContent().substring(0, Math.min(PREVIEW_LENGTH, ctx.getContent().length()));
        return new DocWriteResultVO(documentId, title, ACTION_OVERWRITE, preview, null, null, null);
    }

    private DocWriteResultVO handleList(DocWriteExecuteContext ctx) {
        Long sessionId = ctx.getSessionId();
        if (sessionId == null) {
            return new DocWriteResultVO(null, null, ACTION_LIST, "sessionId is required", null, List.of(), 0);
        }
        List<DocumentVO> docs = documentSpi.listBySession(sessionId, ctx.getPage(), ctx.getPageSize());
        List<DocWriteResultVO.DocReadSummaryVO> summaries = docs.stream()
                .map(d -> {
                    String raw = d.getContent() != null ? d.getContent().replaceAll("[#*`\\n\\r]+", " ").trim() : "";
                    String preview = raw.length() > 100 ? raw.substring(0, 100) + "…" : raw;
                    return new DocWriteResultVO.DocReadSummaryVO(
                            d.getId(), d.getTitle(), preview, d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
                })
                .toList();
        return new DocWriteResultVO(null, null, ACTION_LIST, null, null, summaries, summaries.size());
    }

    private DocWriteResultVO handleGet(DocWriteExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            return new DocWriteResultVO(null, null, ACTION_GET, "documentId is required", null, null, null);
        }
        DocumentVO doc = documentSpi.getById(documentId);
        if (doc == null) {
            return new DocWriteResultVO(null, null, ACTION_GET, "Document not found: " + documentId, null, null, null);
        }
        String preview = doc.getContent() != null
                ? doc.getContent().substring(0, Math.min(PREVIEW_LENGTH, doc.getContent().length()))
                : "";
        return new DocWriteResultVO(documentId, doc.getTitle(), ACTION_GET, preview, doc.getContent(), null, null);
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
}
