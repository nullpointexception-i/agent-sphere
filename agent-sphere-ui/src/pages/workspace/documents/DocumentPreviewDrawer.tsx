import {
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FolderOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { App, Button, Drawer, Empty, Input, Spin } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type {
  DocumentComment,
  DocumentSummary,
} from '@/services/agentSphere/api';
import { agentApi } from '@/services/agentSphere/api';
import { tw } from '../i18n';
import { MessageMarkdown } from '../MessageMarkdown';
import {
  documentDownloadName,
  documentKind,
  documentUpdatedAt,
  downloadDocumentText,
  formatDocumentSize,
} from './documentModel';
import { useStyles } from './style';
import { extractToc } from './wikiModel';

interface DocumentDetail extends DocumentSummary {
  content?: string;
}

const COMMENT_POLL_MS = 30_000;

export function DocumentPreviewDrawer({
  open,
  document,
  mine,
  canEdit,
  onClose,
  onEdit,
  onMove,
}: {
  open: boolean;
  document: DocumentSummary | null;
  mine: boolean;
  canEdit: boolean;
  onClose: () => void;
  onEdit: () => void;
  onMove: () => void;
}) {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const [detail, setDetail] = useState<DocumentDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [comments, setComments] = useState<DocumentComment[]>([]);
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [commentText, setCommentText] = useState('');
  const [quotedText, setQuotedText] = useState('');
  const [replyTo, setReplyTo] = useState<number | undefined>();
  const [submitting, setSubmitting] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [editText, setEditText] = useState('');
  const contentRef = useRef<HTMLDivElement>(null);

  const toc = useMemo(() => extractToc(detail?.content), [detail?.content]);
  const documentId = document?.id;

  const loadComments = async () => {
    if (!documentId) return;
    setCommentsLoading(true);
    try {
      setComments(
        (await agentApi.artifacts.documents.comments(documentId)) ?? [],
      );
    } catch {
      setComments([]);
    } finally {
      setCommentsLoading(false);
    }
  };

  useEffect(() => {
    if (!open || !document) {
      setDetail(null);
      setFailed(false);
      setComments([]);
      return;
    }
    let active = true;
    setLoading(true);
    setFailed(false);
    agentApi.artifacts.documents
      .getById(document.id)
      .then((result) => {
        if (active) setDetail(result as DocumentDetail);
      })
      .catch(() => {
        if (active) setFailed(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [open, document?.id]);

  useEffect(() => {
    if (!open || !documentId) return;
    void loadComments();
    const timer = window.setInterval(() => void loadComments(), COMMENT_POLL_MS);
    return () => window.clearInterval(timer);
  }, [open, documentId]);

  useEffect(() => {
    setCommentText('');
    setQuotedText('');
    setReplyTo(undefined);
    setEditId(null);
  }, [open, documentId]);

  const content = detail?.content || '';
  const kind = detail ? documentKind(detail) : 'markdown';

  const submitComment = async () => {
    if (!documentId) return;
    const text = commentText.trim();
    if (!text) return;
    setSubmitting(true);
    try {
      await agentApi.artifacts.documents.createComment(documentId, {
        content: text,
        quotedText: quotedText || undefined,
        parentId: replyTo,
      });
      setCommentText('');
      setQuotedText('');
      setReplyTo(undefined);
      await loadComments();
    } catch {
      message.error(tw('documents.commentFailed', 'Failed to post comment'));
    } finally {
      setSubmitting(false);
    }
  };

  const saveEdit = async (comment: DocumentComment) => {
    const text = editText.trim();
    if (!text) return;
    try {
      await agentApi.artifacts.documents.updateComment(comment.id, text);
      setEditId(null);
      await loadComments();
    } catch {
      message.error(tw('documents.commentFailed', 'Failed to post comment'));
    }
  };

  const removeComment = (comment: DocumentComment) => {
    modal.confirm({
      title: tw('documents.deleteComment', 'Delete this comment?'),
      content: comment.content,
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await agentApi.artifacts.documents.deleteComment(comment.id);
          await loadComments();
        } catch {
          message.error(tw('documents.commentFailed', 'Failed to post comment'));
        }
      },
    });
  };

  const toggleResolved = async (comment: DocumentComment) => {
    try {
      if (comment.status === 'RESOLVED') {
        await agentApi.artifacts.documents.reopenComment(comment.id);
      } else {
        await agentApi.artifacts.documents.resolveComment(comment.id);
      }
      await loadComments();
    } catch {
      message.error(tw('documents.commentFailed', 'Failed to post comment'));
    }
  };

  const jumpToHeading = (index: number) => {
    const headings =
      contentRef.current?.querySelectorAll<HTMLElement>('h1, h2, h3');
    headings?.[index]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const renderComment = (comment: DocumentComment, reply = false) => (
    <div
      key={comment.id}
      className={`${styles.commentItem} ${reply ? styles.commentReply : ''}`}
    >
      <div className={styles.commentHead}>
        <span className={styles.commentAuthor}>
          {comment.authorName || '-'}
        </span>
        <span>{documentUpdatedAt(comment.createdAt)}</span>
        {comment.status === 'RESOLVED' ? (
          <span>{tw('documents.resolved', 'Resolved')}</span>
        ) : null}
      </div>
      {editId === comment.id ? (
        <div>
          <Input.TextArea
            rows={3}
            value={editText}
            onChange={(event) => setEditText(event.target.value)}
          />
          <div className={styles.commentActions}>
            <a onClick={() => void saveEdit(comment)}>
              {tw('common.save', 'Save')}
            </a>
            <a onClick={() => setEditId(null)}>{tw('common.cancel', 'Cancel')}</a>
          </div>
        </div>
      ) : (
        <>
          {comment.quotedText ? (
            <div className={styles.commentQuote}>{comment.quotedText}</div>
          ) : null}
          <div className={styles.commentContent}>{comment.content}</div>
        </>
      )}
      {editId !== comment.id ? (
        <div className={styles.commentActions}>
          {!reply ? (
            <a
              onClick={() => {
                setReplyTo(comment.id);
                setQuotedText(comment.content.slice(0, 200));
              }}
            >
              {tw('documents.reply', 'Reply')}
            </a>
          ) : null}
          {comment.mine ? (
            <>
              <a
                onClick={() => {
                  setEditId(comment.id);
                  setEditText(comment.content);
                }}
              >
                {tw('documents.editComment', 'Edit')}
              </a>
              <a onClick={() => removeComment(comment)}>
                {tw('documents.deleteCommentAction', 'Delete')}
              </a>
            </>
          ) : null}
          {comment.canModerate ? (
            <>
              <a onClick={() => void toggleResolved(comment)}>
                {comment.status === 'RESOLVED'
                  ? tw('documents.reopen', 'Reopen')
                  : tw('documents.resolve', 'Resolve')}
              </a>
              {!comment.mine ? (
                <a onClick={() => removeComment(comment)}>
                  <DeleteOutlined /> {tw('documents.deleteCommentAction', 'Delete')}
                </a>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );

  const topLevel = comments.filter((comment) => comment.parentId == null);

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={1080}
      title={detail?.title || document?.title || '-'}
      footer={
        <div className={styles.documentPreviewActions}>
          {mine && canEdit ? (
            <Button icon={<FolderOutlined />} onClick={onMove}>
              {tw('documents.moveDocument', 'Move document')}
            </Button>
          ) : null}
          <Button
            icon={<DownloadOutlined />}
            disabled={!detail}
            onClick={() => {
              if (!detail) return;
              downloadDocumentText(
                documentDownloadName(detail),
                detail.content || '',
              );
            }}
          >
            {tw('documents.export', 'Export')}
          </Button>
          {mine && canEdit ? (
            <Button
              type="primary"
              icon={<EditOutlined />}
              disabled={!detail}
              onClick={onEdit}
            >
              {tw('documents.edit', 'Edit')}
            </Button>
          ) : null}
        </div>
      }
    >
      <Spin spinning={loading}>
        {failed ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={tw('documents.loadFailed', 'Failed to load document')}
          />
        ) : detail ? (
          <div className={styles.wikiPage}>
            <div className={styles.wikiPageMain}>
              <div className={styles.wikiPageTitle}>{detail.title || '-'}</div>
              <div className={styles.documentPreviewMeta}>
                <span>
                  {tw('documents.owner', 'Owner')}: {detail.createdBy || '-'}
                </span>
                <span>
                  {tw('documents.updatedAt', 'Updated')}:{' '}
                  {documentUpdatedAt(detail.updatedAt) || '-'}
                </span>
                <span>
                  {tw('documents.version', 'Version')}: {detail.version ?? 1}
                </span>
                <span>
                  {formatDocumentSize(detail.contentLength)}{' '}
                  {tw('documents.chars', 'chars')}
                </span>
                {!mine ? (
                  <span>{tw('documents.readonly', 'Read-only')}</span>
                ) : null}
              </div>
              <div ref={contentRef}>
                {kind === 'markdown' ? (
                  <MessageMarkdown text={content} />
                ) : (
                  <div className={styles.documentPreviewText}>{content}</div>
                )}
              </div>

              <div className={styles.wikiComments}>
                <div className={styles.wikiCommentsHead}>
                  <span>
                    {tw('documents.comments', 'Comments')} ({comments.length})
                  </span>
                </div>
                {commentsLoading && comments.length === 0 ? <Spin /> : null}
                {topLevel.map((comment) => (
                  <div key={comment.id}>
                    {renderComment(comment)}
                    {comments
                      .filter((reply) => reply.parentId === comment.id)
                      .map((reply) => renderComment(reply, true))}
                  </div>
                ))}
                {!commentsLoading && comments.length === 0 ? (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={tw(
                      'documents.noComments',
                      'No comments yet. Start the discussion.',
                    )}
                  />
                ) : null}

                <div className={styles.commentComposer}>
                  {replyTo || quotedText ? (
                    <div className={styles.commentReplyHint}>
                      <span>
                        {replyTo
                          ? tw('documents.replying', 'Replying to a comment')
                          : tw('documents.quoting', 'Quoting a comment')}
                      </span>
                      <a
                        onClick={() => {
                          setReplyTo(undefined);
                          setQuotedText('');
                        }}
                      >
                        {tw('common.cancel', 'Cancel')}
                      </a>
                    </div>
                  ) : null}
                  {quotedText ? (
                    <div className={styles.commentQuote}>{quotedText}</div>
                  ) : null}
                  <Input.TextArea
                    rows={3}
                    value={commentText}
                    maxLength={5000}
                    placeholder={tw(
                      'documents.commentPlaceholder',
                      'Write a comment…',
                    )}
                    onChange={(event) => setCommentText(event.target.value)}
                  />
                  <div style={{ marginTop: 8, textAlign: 'right' }}>
                    <Button
                      type="primary"
                      icon={<SendOutlined />}
                      loading={submitting}
                      disabled={!commentText.trim()}
                      onClick={() => void submitComment()}
                    >
                      {tw('documents.postComment', 'Comment')}
                    </Button>
                  </div>
                </div>
              </div>
            </div>

            <div className={styles.wikiToc}>
              <div className={styles.wikiTocTitle}>
                {tw('documents.toc', 'Contents')}
              </div>
              {toc.length === 0 ? (
                <span style={{ color: '#b7b1c9' }}>
                  {tw('documents.tocEmpty', 'No headings')}
                </span>
              ) : (
                toc.map((item) => (
                  <span
                    key={`${item.index}-${item.text}`}
                    className={`${styles.wikiTocItem} ${
                      item.level === 2
                        ? styles.wikiTocLevel2
                        : item.level === 3
                          ? styles.wikiTocLevel3
                          : ''
                    }`}
                    onClick={() => jumpToHeading(item.index)}
                  >
                    {item.text}
                  </span>
                ))
              )}
            </div>
          </div>
        ) : null}
      </Spin>
    </Drawer>
  );
}
