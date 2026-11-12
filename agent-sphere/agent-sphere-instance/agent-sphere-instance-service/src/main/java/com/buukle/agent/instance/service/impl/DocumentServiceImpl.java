package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.domain.AgentDocument;
import com.buukle.agent.instance.domain.AgentDocumentComment;
import com.buukle.agent.instance.domain.AgentDocumentFolder;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.dto.CreateDocumentFolderDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentCommentDTO;
import com.buukle.agent.instance.dtvo.dto.UpdateDocumentFolderDTO;
import com.buukle.agent.instance.dtvo.vo.DocumentCommentVO;
import com.buukle.agent.instance.dtvo.vo.DocumentFolderVO;
import com.buukle.agent.instance.dtvo.vo.DocumentSummaryVO;
import com.buukle.agent.instance.dtvo.vo.DocumentVO;
import com.buukle.agent.instance.repository.AgentDocumentCommentMapper;
import com.buukle.agent.instance.repository.AgentDocumentFolderMapper;
import com.buukle.agent.instance.repository.AgentDocumentMapper;
import com.buukle.agent.instance.repository.DocumentCommentCount;
import com.buukle.agent.instance.spi.DocumentSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentSpi {

    private static final int PREVIEW_CHARS = 240;
    private static final int MAX_FOLDER_DEPTH = 8;
    private static final String INSTANCE_ROOT_NAME = "分身文档";
    private static final String SYSTEM_KEY_INSTANCE_ROOT = "INSTANCE_ROOT";
    private static final String FOLDER_STATUS_ACTIVE = "ACTIVE";
    private static final String COMMENT_STATUS_OPEN = "OPEN";
    private static final String COMMENT_STATUS_RESOLVED = "RESOLVED";
    private static final String DELETE_MODE_PURGE = "purge";
    private static final long UNFILED_FOLDER_ID = 0L;

    private final AgentDocumentMapper mapper;
    private final AgentDocumentFolderMapper folderMapper;
    private final AgentDocumentCommentMapper commentMapper;
    private final InstanceSpi instanceSpi;

    @Override
    @Transactional
    public Long create(DocumentVO vo) {
        AgentDocument entity = toEntity(vo);
        entity.setVersion(1);
        if (entity.getFolderId() == null) {
            entity.setFolderId(instanceFolderId(vo));
        }
        mapper.insert(entity);
        return entity.getId();
    }

    /** Avatar-created documents always land in that avatar's folder under "分身文档". */
    private Long instanceFolderId(DocumentVO vo) {
        Long instanceId = vo.getInstanceId();
        Long userId = AuthContext.getUserId();
        Long tenantId = TenantContext.get();
        String username = vo.getCreatedBy() != null ? vo.getCreatedBy() : AuthContext.getUsername();
        if (instanceId == null || instanceId <= 0 || userId == null
                || tenantId == null || username == null || username.isBlank()) {
            return null;
        }
        AgentDocumentFolder root = ensureInstanceRoot(userId, tenantId, username);
        AgentDocumentFolder folder = folderMapper.selectOne(
                new LambdaQueryWrapper<AgentDocumentFolder>()
                        .eq(AgentDocumentFolder::getOwnerUserId, userId)
                        .eq(AgentDocumentFolder::getInstanceId, instanceId)
                        .last("LIMIT 1"));
        if (folder != null) {
            return folder.getId();
        }
        String folderName = instanceName(instanceId);
        folderMapper.insertInstanceFolder(tenantId, userId, root.getId(),
                folderName, instanceId, username);
        AgentDocumentFolder created = folderMapper.selectOne(
                new LambdaQueryWrapper<AgentDocumentFolder>()
                        .eq(AgentDocumentFolder::getOwnerUserId, userId)
                        .eq(AgentDocumentFolder::getInstanceId, instanceId)
                        .last("LIMIT 1"));
        return created != null ? created.getId() : null;
    }

    private AgentDocumentFolder ensureInstanceRoot(Long userId, Long tenantId, String username) {
        AgentDocumentFolder root = findRoot(userId);
        if (root != null) {
            return root;
        }
        folderMapper.insertInstanceRoot(tenantId, userId, INSTANCE_ROOT_NAME, username);
        AgentDocumentFolder created = findRoot(userId);
        if (created == null) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, "failed to create folder root");
        }
        return created;
    }

    private String instanceName(Long instanceId) {
        try {
            var instance = instanceSpi.getInstance(instanceId);
            if (instance != null && instance.getName() != null && !instance.getName().isBlank()) {
                return instance.getName();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the id when the instance is no longer visible.
        }
        return "实例 " + instanceId;
    }

    private AgentDocumentFolder findRoot(Long userId) {
        return folderMapper.selectOne(new LambdaQueryWrapper<AgentDocumentFolder>()
                .eq(AgentDocumentFolder::getOwnerUserId, userId)
                .eq(AgentDocumentFolder::getSystemKey, SYSTEM_KEY_INSTANCE_ROOT)
                .last("LIMIT 1"));
    }

    @Override
    public DocumentVO getById(Long id) {
        TenantScope saved = TenantScopeHolder.get();
        String username = AuthContext.getUsername();
        Long tenantId = TenantContext.get();
        try {
            // Wiki pages are readable across the tenant, not only by their creator.
            if (username != null && !username.isBlank() && tenantId != null) {
                TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, username));
            }
            AgentDocument entity = mapper.selectById(id);
            return entity != null ? toVo(entity) : null;
        } finally {
            restoreScope(saved);
        }
    }

    @Override
    public List<DocumentVO> listBySession(Long sessionId, int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getSessionId, sessionId)
                        .orderByDesc(AgentDocument::getUpdatedAt)
        );
        return p.getRecords().stream().map(this::toVo).toList();
    }

    @Override
    public List<DocumentVO> listByInstanceAndCreator(Long instanceId, String createdBy, int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getInstanceId, instanceId)
                        .eq(AgentDocument::getCreatedBy, createdBy)
                        .orderByDesc(AgentDocument::getUpdatedAt)
        );
        return p.getRecords().stream().map(this::toVo).toList();
    }

    @Override
    public PageResult<DocumentVO> listAll(int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .orderByDesc(AgentDocument::getUpdatedAt)
        );
        List<DocumentVO> records = p.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(records, p.getTotal(), page, size);
    }

    @Override
    public PageResult<DocumentSummaryVO> listMine(String username, String keyword, Long folderId,
                                                  int page, int size) {
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        LambdaQueryWrapper<AgentDocument> query = new LambdaQueryWrapper<AgentDocument>()
                .eq(AgentDocument::getCreatedBy, username)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(AgentDocument::getTitle, keyword)
                                .or().like(AgentDocument::getContent, keyword))
                .orderByDesc(AgentDocument::getUpdatedAt);
        if (folderId != null) {
            if (folderId == UNFILED_FOLDER_ID) {
                query.isNull(AgentDocument::getFolderId);
            } else {
                query.eq(AgentDocument::getFolderId, folderId);
            }
        }
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.max(1, size)), query);
        List<DocumentSummaryVO> records = p.getRecords().stream().map(this::toSummary).toList();
        fillCommentCounts(records);
        return PageResult.of(records, p.getTotal(), page, size);
    }

    @Override
    public PageResult<DocumentSummaryVO> listTeam(String username, Long tenantId, String keyword,
                                                  int page, int size) {
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        if (tenantId == null) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        TenantScope saved = TenantScopeHolder.get();
        try {
            // Team docs are readable by the whole tenant, but they are not personal
            // data: drop selfOnly for this query only, never the tenant condition.
            TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, username));
            Page<AgentDocument> p = mapper.selectPage(
                    new Page<>(Math.max(1, page), Math.max(1, size)),
                    new LambdaQueryWrapper<AgentDocument>()
                            .and(w -> w.ne(AgentDocument::getCreatedBy, username)
                                    .or().isNull(AgentDocument::getCreatedBy))
                            .and(keyword != null && !keyword.isBlank(),
                                    w -> w.like(AgentDocument::getTitle, keyword)
                                            .or().like(AgentDocument::getContent, keyword))
                            .orderByDesc(AgentDocument::getUpdatedAt)
            );
            List<DocumentSummaryVO> records = p.getRecords().stream().map(this::toSummary).toList();
            fillCommentCounts(records);
            return PageResult.of(records, p.getTotal(), page, size);
        } finally {
            restoreScope(saved);
        }
    }

    @Override
    @Transactional
    public void update(Long id, String title, String content) {
        AgentDocument existing = mapper.selectById(id);
        if (existing == null) {
            return;
        }
        assertCanWrite(existing);
        AgentDocument entity = new AgentDocument();
        entity.setId(id);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setVersion((existing.getVersion() == null ? 0 : existing.getVersion()) + 1);
        mapper.updateById(entity);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        AgentDocument existing = mapper.selectById(id);
        if (existing == null) {
            return;
        }
        assertCanWrite(existing);
        mapper.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (AgentDocument existing : mapper.selectBatchIds(ids)) {
            assertCanWrite(existing);
        }
        mapper.deleteBatchIds(ids);
    }

    @Override
    public long countByInstanceAndCreator(Long instanceId, String createdBy) {
        return mapper.selectCount(new LambdaQueryWrapper<AgentDocument>()
                .eq(AgentDocument::getInstanceId, instanceId)
                .eq(AgentDocument::getCreatedBy, createdBy));
    }

    @Override
    public List<DocumentVO> searchByTitle(Long instanceId, String createdBy, String keyword, int page, int size) {
        Page<AgentDocument> p = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getInstanceId, instanceId)
                        .eq(AgentDocument::getCreatedBy, createdBy)
                        .like(AgentDocument::getTitle, keyword)
                        .orderByDesc(AgentDocument::getId)
        );
        return p.getRecords().stream().map(this::toVo).toList();
    }

    @Override
    public String createShareToken(Long documentId) {
        AgentDocument doc = mapper.selectById(documentId);
        if (doc == null) return null;
        if (doc.getShareToken() != null) return doc.getShareToken();
        String token = UUID.randomUUID().toString().replace("-", "");
        AgentDocument update = new AgentDocument();
        update.setId(documentId);
        update.setShareToken(token);
        mapper.updateById(update);
        return token;
    }

    @Override
    public DocumentVO getByShareToken(String token) {
        AgentDocument doc = mapper.selectOne(
                new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getShareToken, token));
        return doc != null ? toVo(doc) : null;
    }

    // ---------------------------------------------------------------- folders

    @Override
    public List<DocumentFolderVO> listFolders(String username, Long tenantId) {
        Long ownerId = requireOwnerId();
        List<AgentDocumentFolder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<AgentDocumentFolder>()
                        .eq(AgentDocumentFolder::getOwnerUserId, ownerId)
                        .orderByAsc(AgentDocumentFolder::getSortOrder)
                        .orderByAsc(AgentDocumentFolder::getId));
        Map<Long, Long> counts = documentCounts(username);
        return folders.stream().map(folder -> toFolderVo(folder, counts)).toList();
    }

    @Override
    @Transactional
    public DocumentFolderVO createFolder(String username, Long tenantId, CreateDocumentFolderDTO dto) {
        Long ownerId = requireOwnerId();
        String name = normalizeFolderName(dto.getName());
        Long parentId = dto.getParentId();
        AgentDocumentFolder parent = validateParent(ownerId, parentId);
        assertFolderNameAvailable(ownerId, parentId, name, null);
        int depth = parent == null ? 1 : depthOf(parent) + 1;
        if (depth > MAX_FOLDER_DEPTH) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "folder depth must not exceed " + MAX_FOLDER_DEPTH);
        }
        AgentDocumentFolder folder = new AgentDocumentFolder();
        folder.setTenantId(tenantId);
        folder.setOwnerUserId(ownerId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setSortOrder(0);
        folder.setStatus(FOLDER_STATUS_ACTIVE);
        folderMapper.insert(folder);
        return toFolderVo(folder, Map.of());
    }

    @Override
    @Transactional
    public DocumentFolderVO updateFolder(String username, Long folderId, UpdateDocumentFolderDTO dto) {
        Long ownerId = requireOwnerId();
        AgentDocumentFolder folder = requireOwnedFolder(ownerId, folderId);
        assertNotSystemFolder(folder);
        String name = folder.getName();
        if (dto.getName() != null && !dto.getName().isBlank()) {
            name = normalizeFolderName(dto.getName());
        }
        Long newParentId = folder.getParentId();
        if (Boolean.TRUE.equals(dto.getMoveToRoot())) {
            newParentId = null;
        } else if (dto.getParentId() != null) {
            newParentId = dto.getParentId();
        }
        boolean moved = !Objects.equals(newParentId, folder.getParentId());
        if (moved) {
            validateParent(ownerId, newParentId);
            assertNotDescendant(ownerId, folderId, newParentId);
            int newDepth = newParentId == null ? 0 : depthOf(requireOwnedFolder(ownerId, newParentId));
            if (newDepth + subtreeHeight(ownerId, folderId) > MAX_FOLDER_DEPTH) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "folder depth must not exceed " + MAX_FOLDER_DEPTH);
            }
        }
        if (!Objects.equals(name, folder.getName()) || moved) {
            assertFolderNameAvailable(ownerId, newParentId, name, folderId);
        }
        AgentDocumentFolder update = new AgentDocumentFolder();
        update.setId(folderId);
        update.setName(name);
        if (moved) {
            update.setParentId(newParentId);
        }
        folderMapper.updateById(update);
        folder.setName(name);
        folder.setParentId(newParentId);
        return toFolderVo(folder, Map.of());
    }

    @Override
    @Transactional
    public void deleteFolder(String username, Long folderId, String mode) {
        Long ownerId = requireOwnerId();
        AgentDocumentFolder folder = requireOwnedFolder(ownerId, folderId);
        assertNotSystemFolder(folder);
        List<Long> ids = new ArrayList<>();
        collectDescendants(ownerId, folderId, ids);
        if (DELETE_MODE_PURGE.equalsIgnoreCase(mode)) {
            for (Long id : ids) {
                mapper.delete(new LambdaQueryWrapper<AgentDocument>()
                        .eq(AgentDocument::getFolderId, id));
            }
            folderMapper.deleteBatchIds(ids);
            return;
        }
        Long parentId = folder.getParentId();
        List<AgentDocumentFolder> children = folderMapper.selectList(
                new LambdaQueryWrapper<AgentDocumentFolder>()
                        .eq(AgentDocumentFolder::getOwnerUserId, ownerId)
                        .eq(AgentDocumentFolder::getParentId, folderId));
        for (AgentDocumentFolder child : children) {
            AgentDocumentFolder move = new AgentDocumentFolder();
            move.setId(child.getId());
            move.setParentId(parentId);
            folderMapper.updateById(move);
        }
        AgentDocument update = new AgentDocument();
        update.setFolderId(parentId);
        mapper.update(update, new LambdaQueryWrapper<AgentDocument>()
                .eq(AgentDocument::getFolderId, folderId));
        folderMapper.deleteById(folderId);
    }

    @Override
    @Transactional
    public void moveDocument(Long id, Long folderId) {
        AgentDocument existing = mapper.selectById(id);
        if (existing == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "document not found");
        }
        assertCanWrite(existing);
        Long ownerId = requireOwnerId();
        if (folderId != null && folderId > 0) {
            requireOwnedFolder(ownerId, folderId);
        }
        AgentDocument update = new AgentDocument();
        update.setId(id);
        update.setFolderId(folderId == null || folderId <= 0 ? null : folderId);
        mapper.updateById(update);
    }

    private Long requireOwnerId() {
        Long ownerId = AuthContext.getUserId();
        if (ownerId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return ownerId;
    }

    private AgentDocumentFolder requireOwnedFolder(Long ownerId, Long folderId) {
        AgentDocumentFolder folder = folderMapper.selectById(folderId);
        if (folder == null || !Objects.equals(folder.getOwnerUserId(), ownerId)) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "folder not found");
        }
        return folder;
    }

    private AgentDocumentFolder validateParent(Long ownerId, Long parentId) {
        return parentId == null ? null : requireOwnedFolder(ownerId, parentId);
    }

    private void assertNotSystemFolder(AgentDocumentFolder folder) {
        if (folder.getSystemKey() != null) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "system folder cannot be changed");
        }
    }

    private void assertFolderNameAvailable(Long ownerId, Long parentId, String name,
                                           Long excludeFolderId) {
        Long count = folderMapper.selectCount(new LambdaQueryWrapper<AgentDocumentFolder>()
                .eq(AgentDocumentFolder::getOwnerUserId, ownerId)
                .eq(parentId != null, AgentDocumentFolder::getParentId, parentId)
                .isNull(parentId == null, AgentDocumentFolder::getParentId)
                .eq(AgentDocumentFolder::getName, name)
                .isNull(AgentDocumentFolder::getInstanceId)
                .isNull(AgentDocumentFolder::getSystemKey)
                .ne(excludeFolderId != null, AgentDocumentFolder::getId, excludeFolderId));
        if (count != null && count > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "folder name already exists");
        }
    }

    private int depthOf(AgentDocumentFolder folder) {
        int depth = 0;
        AgentDocumentFolder current = folder;
        Set<Long> seen = new HashSet<>();
        while (current != null && seen.add(current.getId())) {
            depth++;
            current = current.getParentId() == null ? null
                    : folderMapper.selectById(current.getParentId());
        }
        return depth;
    }

    private int subtreeHeight(Long ownerId, Long folderId) {
        List<AgentDocumentFolder> children = folderMapper.selectList(
                new LambdaQueryWrapper<AgentDocumentFolder>()
                        .eq(AgentDocumentFolder::getOwnerUserId, ownerId)
                        .eq(AgentDocumentFolder::getParentId, folderId));
        int height = 1;
        for (AgentDocumentFolder child : children) {
            height = Math.max(height, 1 + subtreeHeight(ownerId, child.getId()));
        }
        return height;
    }

    private void assertNotDescendant(Long ownerId, Long folderId, Long newParentId) {
        if (newParentId == null) return;
        if (newParentId.equals(folderId)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "folder cannot contain itself");
        }
        Long cursor = newParentId;
        Set<Long> seen = new HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            if (cursor.equals(folderId)) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "folder cannot move into its own descendant");
            }
            AgentDocumentFolder node = folderMapper.selectById(cursor);
            cursor = node == null ? null : node.getParentId();
        }
    }

    private void collectDescendants(Long ownerId, Long folderId, List<Long> result) {
        result.add(folderId);
        List<AgentDocumentFolder> children = folderMapper.selectList(
                new LambdaQueryWrapper<AgentDocumentFolder>()
                        .eq(AgentDocumentFolder::getOwnerUserId, ownerId)
                        .eq(AgentDocumentFolder::getParentId, folderId));
        for (AgentDocumentFolder child : children) {
            collectDescendants(ownerId, child.getId(), result);
        }
    }

    private Map<Long, Long> documentCounts(String username) {
        Map<Long, Long> counts = new HashMap<>();
        if (username == null || username.isBlank()) return counts;
        List<AgentDocument> documents = mapper.selectList(
                new LambdaQueryWrapper<AgentDocument>()
                        .select(AgentDocument::getFolderId)
                        .eq(AgentDocument::getCreatedBy, username)
                        .isNotNull(AgentDocument::getFolderId));
        for (AgentDocument document : documents) {
            counts.merge(document.getFolderId(), 1L, Long::sum);
        }
        return counts;
    }

    private DocumentFolderVO toFolderVo(AgentDocumentFolder folder, Map<Long, Long> counts) {
        DocumentFolderVO vo = new DocumentFolderVO();
        vo.setId(folder.getId());
        vo.setParentId(folder.getParentId());
        vo.setName(folder.getName());
        vo.setInstanceId(folder.getInstanceId());
        vo.setSystemKey(folder.getSystemKey());
        vo.setSortOrder(folder.getSortOrder());
        vo.setDocumentCount(counts.getOrDefault(folder.getId(), 0L));
        return vo;
    }

    // --------------------------------------------------------------- comments

    @Override
    public List<DocumentCommentVO> listComments(Long documentId) {
        DocumentVO document = requireReadableDocument(documentId);
        List<AgentDocumentComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<AgentDocumentComment>()
                        .eq(AgentDocumentComment::getDocumentId, documentId)
                        .orderByAsc(AgentDocumentComment::getCreatedAt)
                        .orderByAsc(AgentDocumentComment::getId));
        return comments.stream().map(comment -> toCommentVo(comment, document)).toList();
    }

    @Override
    @Transactional
    public DocumentCommentVO createComment(Long documentId, CreateDocumentCommentDTO dto) {
        DocumentVO document = requireReadableDocument(documentId);
        Long parentId = dto.getParentId();
        if (parentId != null) {
            AgentDocumentComment parent = commentMapper.selectById(parentId);
            if (parent == null || !Objects.equals(parent.getDocumentId(), documentId)) {
                throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "comment not found");
            }
            if (parent.getParentId() != null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "replies are limited to one level");
            }
        }
        AgentDocumentComment comment = new AgentDocumentComment();
        comment.setDocumentId(documentId);
        comment.setParentId(parentId);
        comment.setAuthorUserId(AuthContext.getUserId());
        comment.setAuthorName(displayName());
        comment.setCreatedBy(AuthContext.getUsername());
        comment.setUpdatedBy(AuthContext.getUsername());
        comment.setContent(dto.getContent().trim());
        comment.setQuotedText(trimToNull(dto.getQuotedText()));
        comment.setStatus(COMMENT_STATUS_OPEN);
        commentMapper.insert(comment);
        return toCommentVo(comment, document);
    }

    @Override
    @Transactional
    public DocumentCommentVO updateComment(Long commentId, UpdateDocumentCommentDTO dto) {
        AgentDocumentComment comment = requireComment(commentId);
        if (!isCommentAuthor(comment) && !AuthContext.isSuperAdmin()) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        AgentDocumentComment update = new AgentDocumentComment();
        update.setId(commentId);
        update.setContent(dto.getContent().trim());
        commentMapper.updateById(update);
        comment.setContent(dto.getContent().trim());
        return toCommentVo(comment, requireReadableDocument(comment.getDocumentId()));
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        AgentDocumentComment comment = requireComment(commentId);
        DocumentVO document = requireReadableDocument(comment.getDocumentId());
        if (!isCommentAuthor(comment) && !canModerate(document)) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        commentMapper.deleteById(commentId);
    }

    @Override
    @Transactional
    public DocumentCommentVO setCommentResolved(Long commentId, boolean resolved) {
        AgentDocumentComment comment = requireComment(commentId);
        DocumentVO document = requireReadableDocument(comment.getDocumentId());
        if (!canModerate(document)) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        AgentDocumentComment update = new AgentDocumentComment();
        update.setId(commentId);
        update.setStatus(resolved ? COMMENT_STATUS_RESOLVED : COMMENT_STATUS_OPEN);
        update.setResolvedBy(resolved ? AuthContext.getUserId() : null);
        update.setResolvedAt(resolved ? LocalDateTime.now() : null);
        commentMapper.updateById(update);
        comment.setStatus(update.getStatus());
        comment.setResolvedAt(update.getResolvedAt());
        return toCommentVo(comment, document);
    }

    private DocumentVO requireReadableDocument(Long documentId) {
        DocumentVO document = getById(documentId);
        if (document == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "document not found");
        }
        return document;
    }

    private AgentDocumentComment requireComment(Long commentId) {
        AgentDocumentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "comment not found");
        }
        return comment;
    }

    private boolean isCommentAuthor(AgentDocumentComment comment) {
        String username = AuthContext.getUsername();
        return username != null && username.equals(comment.getCreatedBy());
    }

    private boolean canModerate(DocumentVO document) {
        if (AuthContext.isSuperAdmin()) return true;
        String username = AuthContext.getUsername();
        if (username != null && username.equals(document.getCreatedBy())) return true;
        return AuthContext.hasPermission(PermissionCodes.INSTANCE_TENANT);
    }

    private DocumentCommentVO toCommentVo(AgentDocumentComment comment, DocumentVO document) {
        DocumentCommentVO vo = new DocumentCommentVO();
        vo.setId(comment.getId());
        vo.setDocumentId(comment.getDocumentId());
        vo.setParentId(comment.getParentId());
        vo.setAuthorName(comment.getAuthorName());
        vo.setContent(comment.getContent());
        vo.setQuotedText(comment.getQuotedText());
        vo.setStatus(comment.getStatus());
        vo.setResolvedAt(comment.getResolvedAt());
        vo.setCreatedAt(comment.getCreatedAt());
        vo.setUpdatedAt(comment.getUpdatedAt());
        vo.setMine(isCommentAuthor(comment));
        vo.setCanModerate(canModerate(document));
        return vo;
    }

    // ------------------------------------------------------------------ utils

    private void restoreScope(TenantScope saved) {
        if (saved != null) {
            TenantScopeHolder.start(saved);
        } else {
            TenantScopeHolder.clear();
        }
    }

    private void fillCommentCounts(List<DocumentSummaryVO> records) {
        if (records.isEmpty()) return;
        List<Long> ids = records.stream().map(DocumentSummaryVO::getId).toList();
        Map<Long, Long> counts = commentMapper.countByDocumentIds(ids).stream()
                .collect(Collectors.toMap(DocumentCommentCount::getDocumentId,
                        DocumentCommentCount::getCommentCount));
        for (DocumentSummaryVO record : records) {
            record.setCommentCount(counts.getOrDefault(record.getId(), 0L));
        }
    }

    private static String normalizeFolderName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "invalid folder name");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String displayName() {
        String name = AuthContext.getDisplayName();
        return name == null || name.isBlank() ? AuthContext.getUsername() : name;
    }

    private AgentDocument toEntity(DocumentVO vo) {
        AgentDocument e = new AgentDocument();
        e.setTitle(vo.getTitle());
        e.setContent(vo.getContent());
        e.setContentType(vo.getContentType() != null ? vo.getContentType() : "markdown");
        e.setSessionId(vo.getSessionId());
        e.setInstanceId(vo.getInstanceId());
        e.setRunId(vo.getRunId());
        e.setFolderId(vo.getFolderId());
        e.setCreatedBy(vo.getCreatedBy());
        return e;
    }

    private DocumentVO toVo(AgentDocument e) {
        DocumentVO vo = new DocumentVO();
        vo.setId(e.getId());
        vo.setTitle(e.getTitle());
        vo.setContent(e.getContent());
        vo.setContentType(e.getContentType());
        vo.setVersion(e.getVersion());
        vo.setSessionId(e.getSessionId());
        vo.setInstanceId(e.getInstanceId());
        vo.setFolderId(e.getFolderId());
        vo.setRunId(e.getRunId());
        vo.setShareToken(e.getShareToken());
        vo.setCreatedBy(e.getCreatedBy());
        vo.setUpdatedBy(e.getUpdatedBy());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    private DocumentSummaryVO toSummary(AgentDocument e) {
        DocumentSummaryVO vo = new DocumentSummaryVO();
        vo.setId(e.getId());
        vo.setTitle(e.getTitle());
        vo.setContentType(e.getContentType());
        vo.setVersion(e.getVersion());
        vo.setSessionId(e.getSessionId());
        vo.setInstanceId(e.getInstanceId());
        vo.setFolderId(e.getFolderId());
        vo.setCreatedBy(e.getCreatedBy());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        String content = e.getContent();
        vo.setContentLength(content == null ? 0 : content.length());
        vo.setPreview(content == null ? null
                : content.substring(0, Math.min(PREVIEW_CHARS, content.length())));
        vo.setCommentCount(0L);
        return vo;
    }

    /** Workspace team documents are read-only: only the creator (or a super admin) may write. */
    private void assertCanWrite(AgentDocument document) {
        if (document == null || AuthContext.isSuperAdmin()) {
            return;
        }
        String username = AuthContext.getUsername();
        // Internal runtime calls may not carry a username; they are not user edits.
        if (username == null || username.isBlank()) {
            return;
        }
        if (!Objects.equals(document.getCreatedBy(), username)) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
