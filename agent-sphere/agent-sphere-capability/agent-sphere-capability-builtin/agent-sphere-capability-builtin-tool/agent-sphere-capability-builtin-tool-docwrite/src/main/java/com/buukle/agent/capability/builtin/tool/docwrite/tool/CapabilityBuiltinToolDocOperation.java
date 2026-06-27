package com.buukle.agent.capability.builtin.tool.docwrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.dto.DocWriteExecuteContext;
import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo.DocWriteResultVO;
import com.buukle.agent.capability.builtin.tool.docwrite.dtvo.vo.HeadingInfo;
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
    static final String ACTION_PATCH = "patch";

    static final String OP_REPLACE_SECTION = "replace_section";
    static final String OP_INSERT_AFTER = "insert_after";
    static final String OP_REPLACE_TEXT = "replace_text";

    private static final String TOOL_DESCRIPTION = """
            Create, append to, overwrite, list, read, or patch a user-visible document.
            action=create: create a new document (requires title, content)
            action=append: add content to an existing document (requires documentId, content, optional title)
            action=overwrite: replace the content of an existing document (requires documentId, content, optional title)
            action=list: list all documents in this session (returns id, title, preview per doc)
            action=get: get content from a specific document (requires documentId).
              Optional modes to control what to fetch:
              - structure=true: return only the outline (headings with line numbers)
              - sectionHeading=text: return the section under the matching heading
              - startLine=n (with optional endLine=m): return specific line range
              Default (no options): return the full document content.
            action=patch: make targeted edits to a document without overwriting the full content (requires documentId, operation).
              operation=replace_section: replace the section under a matching heading (requires headingSearch, content)
              operation=insert_after: insert new content after a matching heading (requires headingSearch, content)
              operation=replace_text: replace specific text in the document (requires searchText, replaceText)
              All patch operations internally do a read-modify-write cycle.
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
        try {
            DocWriteExecuteContext dwCtx = (DocWriteExecuteContext) ctx;
            String action = dwCtx.getAction();
            if (action == null) {
                log.warn("docwrite action is null");
                DocWriteResultVO r = new DocWriteResultVO();
                r.setAction("unknown");
                r.setPreview("action is required");
                return r;
            }
            Long documentId = dwCtx.getDocumentId();
            return switch (action) {
            case ACTION_CREATE -> handleCreate(dwCtx);
            case ACTION_APPEND -> handleAppend(dwCtx, documentId);
            case ACTION_OVERWRITE -> handleOverwrite(dwCtx, documentId);
            case ACTION_LIST -> handleList(dwCtx);
            case ACTION_GET -> handleGet(dwCtx, documentId);
            case ACTION_PATCH -> handlePatch(dwCtx, documentId);
            default -> {
                log.warn("Unknown docwrite action: {}", action);
                DocWriteResultVO r = new DocWriteResultVO();
                r.setAction(action);
                yield r;
            }
        };
        } catch (Exception e) {
            log.warn("docwrite execute failed action={}", ctx instanceof DocWriteExecuteContext d ? d.getAction() : "unknown", e);
            DocWriteResultVO r = new DocWriteResultVO();
            r.setAction("error");
            r.setPreview("Internal error: " + e.getMessage());
            return r;
        }
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
        DocWriteResultVO r = new DocWriteResultVO();
        r.setDocumentId(newId);
        r.setTitle(vo.getTitle());
        r.setAction(ACTION_CREATE);
        r.setPreview(preview);
        return r;
    }

    private DocWriteResultVO handleAppend(DocWriteExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setAction(ACTION_APPEND);
            r.setPreview("documentId is required for append");
            return r;
        }
        DocumentVO existing = documentSpi.getById(documentId);
        if (existing == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setDocumentId(documentId);
            r.setAction(ACTION_APPEND);
            r.setPreview("Document not found: " + documentId);
            return r;
        }
        String newContent = existing.getContent() + "\n\n" + ctx.getContent();
        String title = ctx.getTitle() != null && !ctx.getTitle().isBlank() ? ctx.getTitle() : existing.getTitle();
        documentSpi.update(documentId, title, newContent);
        String preview = newContent.substring(0, Math.min(PREVIEW_LENGTH, newContent.length()));
        DocWriteResultVO r = new DocWriteResultVO();
        r.setDocumentId(documentId);
        r.setTitle(title);
        r.setAction(ACTION_APPEND);
        r.setPreview(preview);
        return r;
    }

    private DocWriteResultVO handleOverwrite(DocWriteExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setAction(ACTION_OVERWRITE);
            r.setPreview("documentId is required for overwrite");
            return r;
        }
        DocumentVO existing = documentSpi.getById(documentId);
        if (existing == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setDocumentId(documentId);
            r.setAction(ACTION_OVERWRITE);
            r.setPreview("Document not found: " + documentId);
            return r;
        }
        String title = ctx.getTitle() != null && !ctx.getTitle().isBlank() ? ctx.getTitle() : existing.getTitle();
        documentSpi.update(documentId, title, ctx.getContent());
        String preview = ctx.getContent().substring(0, Math.min(PREVIEW_LENGTH, ctx.getContent().length()));
        DocWriteResultVO r = new DocWriteResultVO();
        r.setDocumentId(documentId);
        r.setTitle(title);
        r.setAction(ACTION_OVERWRITE);
        r.setPreview(preview);
        return r;
    }

    private DocWriteResultVO handleList(DocWriteExecuteContext ctx) {
        Long sessionId = ctx.getSessionId();
        if (sessionId == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setAction(ACTION_LIST);
            r.setPreview("sessionId is required");
            return r;
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
        DocWriteResultVO r = new DocWriteResultVO();
        r.setAction(ACTION_LIST);
        r.setDocuments(summaries);
        r.setTotal(summaries.size());
        return r;
    }

    private DocWriteResultVO handleGet(DocWriteExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setAction(ACTION_GET);
            r.setPreview("documentId is required");
            return r;
        }
        DocumentVO doc = documentSpi.getById(documentId);
        if (doc == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setAction(ACTION_GET);
            r.setPreview("Document not found: " + documentId);
            return r;
        }

        String content = doc.getContent() != null ? doc.getContent() : "";
        String[] lines = content.split("\n", -1);

        // Structure mode: return outline only
        if (Boolean.TRUE.equals(ctx.getStructure())) {
            List<HeadingInfo> headings = MarkdownParser.parseHeadings(content);
            DocWriteResultVO r = baseResult(doc);
            r.setHeadings(headings);
            r.setTotalLines(lines.length);
            r.setTotal(headings.size());
            return r;
        }

        // Section mode: return content under a matching heading
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
            String preview = section.length() > PREVIEW_LENGTH ? section.substring(0, PREVIEW_LENGTH) + "..." : section;
            DocWriteResultVO r = baseResult(doc);
            r.setContent(section);
            r.setPreview(preview);
            return r;
        }

        // Line range mode: return specific lines
        if (ctx.getStartLine() != null) {
            String extracted = MarkdownParser.extractLines(content, ctx.getStartLine(), ctx.getEndLine());
            String preview = extracted.length() > PREVIEW_LENGTH ? extracted.substring(0, PREVIEW_LENGTH) + "..." : extracted;
            DocWriteResultVO r = baseResult(doc);
            r.setContent(extracted);
            r.setPreview(preview);
            return r;
        }

        // Default: full content
        String preview = content.length() > PREVIEW_LENGTH ? content.substring(0, PREVIEW_LENGTH) + "..." : content;
        DocWriteResultVO r = baseResult(doc);
        r.setContent(content);
        r.setPreview(preview);
        return r;
    }

    private DocWriteResultVO handlePatch(DocWriteExecuteContext ctx, Long documentId) {
        if (documentId == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setAction(ACTION_PATCH);
            r.setPreview("documentId is required");
            return r;
        }
        String op = ctx.getOperation();
        if (op == null || op.isBlank()) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setDocumentId(documentId);
            r.setAction(ACTION_PATCH);
            r.setPreview("operation is required (replace_section, insert_after, replace_text)");
            return r;
        }
        DocumentVO doc = documentSpi.getById(documentId);
        if (doc == null) {
            DocWriteResultVO r = new DocWriteResultVO();
            r.setDocumentId(documentId);
            r.setAction(ACTION_PATCH);
            r.setPreview("Document not found: " + documentId);
            return r;
        }

        String content = doc.getContent() != null ? doc.getContent() : "";
        String title = doc.getTitle();
        String newContent;

        try {
            switch (op) {
                case OP_REPLACE_SECTION -> {
                    String headingSearch = ctx.getHeadingSearch();
                if (headingSearch == null || headingSearch.isBlank()) {
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("headingSearch is required for replace_section");
                    return r;
                }
                String section = MarkdownParser.extractSection(content, headingSearch);
                if (section == null) {
                    List<HeadingInfo> headings = MarkdownParser.parseHeadings(content);
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("Section not found: \"" + headingSearch + "\". Available headings:");
                    r.setHeadings(headings);
                    r.setTotalLines(content.split("\n", -1).length);
                    return r;
                }
                String newSection = ctx.getContent();
                if (newSection == null) newSection = "";
                String replaced = MarkdownParser.replaceSection(content, section, newSection);
                if (replaced == null) {
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("Section found but replacement failed — possible encoding or whitespace mismatch. Use get(sectionHeading=...) to verify the section content before retrying.");
                    return r;
                }
                newContent = replaced;
            }
            case OP_INSERT_AFTER -> {
                String headingSearch = ctx.getHeadingSearch();
                if (headingSearch == null || headingSearch.isBlank()) {
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("headingSearch is required for insert_after");
                    return r;
                }
                String inserted = MarkdownParser.insertAfterSection(content, headingSearch, ctx.getContent());
                if (inserted == null) {
                    List<HeadingInfo> headings = MarkdownParser.parseHeadings(content);
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("Heading not found: \"" + headingSearch + "\". Available headings:");
                    r.setHeadings(headings);
                    r.setTotalLines(content.split("\n", -1).length);
                    return r;
                }
                newContent = inserted;
            }
            case OP_REPLACE_TEXT -> {
                String searchText = ctx.getSearchText();
                String replaceText = ctx.getReplaceText();
                if (searchText == null || searchText.isBlank() || replaceText == null) {
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("searchText and replaceText are required for replace_text");
                    return r;
                }
                String replaced = MarkdownParser.replaceFirst(content, searchText, replaceText);
                if (replaced == null) {
                    int first = content.indexOf(searchText);
                    if (first == -1) {
                        DocWriteResultVO r = new DocWriteResultVO();
                        r.setDocumentId(documentId);
                        r.setAction(ACTION_PATCH);
                        r.setPreview("Text not found: \"" + searchText + "\". Use get(action=get, documentId=" + documentId + ") to verify the text.");
                        return r;
                    }
                    int second = content.indexOf(searchText, first + searchText.length());
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("Text found " + (second != -1 ? "multiple" : "unexpected") + " times. Use a more specific searchText or use get to confirm.");
                    return r;
                }
                newContent = replaced;
            }
                default -> {
                    DocWriteResultVO r = new DocWriteResultVO();
                    r.setDocumentId(documentId);
                    r.setAction(ACTION_PATCH);
                    r.setPreview("Unknown operation: " + op + ". Supported: replace_section, insert_after, replace_text");
                    return r;
                }
            }
        } catch (Exception e) {
            log.warn("patch failed documentId={} op={}", documentId, op, e);
            DocWriteResultVO r = new DocWriteResultVO();
            r.setDocumentId(documentId);
            r.setAction(ACTION_PATCH);
            r.setPreview("Internal error: " + e.getMessage());
            return r;
        }

        documentSpi.update(documentId, title, newContent);
        String preview = newContent.length() > PREVIEW_LENGTH ? newContent.substring(0, PREVIEW_LENGTH) + "..." : newContent;
        DocWriteResultVO r = new DocWriteResultVO();
        r.setDocumentId(documentId);
        r.setTitle(title);
        r.setAction(ACTION_PATCH);
        r.setPreview(preview);
        return r;
    }

    private DocWriteResultVO baseResult(DocumentVO doc) {
        DocWriteResultVO r = new DocWriteResultVO();
        r.setDocumentId(doc.getId());
        r.setTitle(doc.getTitle());
        r.setAction(ACTION_GET);
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
}
