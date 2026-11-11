import { DownloadOutlined, EditOutlined } from '@ant-design/icons';
import { Button, Drawer, Empty, Spin } from 'antd';
import { useEffect, useState } from 'react';
import type { DocumentSummary } from '@/services/agentSphere/api';
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

interface DocumentDetail extends DocumentSummary {
  content?: string;
}

export function DocumentPreviewDrawer({
  open,
  document,
  mine,
  canEdit,
  onClose,
  onEdit,
}: {
  open: boolean;
  document: DocumentSummary | null;
  mine: boolean;
  canEdit: boolean;
  onClose: () => void;
  onEdit: () => void;
}) {
  const { styles } = useStyles();
  const [detail, setDetail] = useState<DocumentDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!open || !document) {
      setDetail(null);
      setFailed(false);
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

  const content = detail?.content || '';
  const kind = detail ? documentKind(detail) : 'markdown';

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={720}
      title={detail?.title || document?.title || '-'}
      footer={
        <div className={styles.documentPreviewActions}>
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
          <>
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
            </div>
            {kind === 'markdown' ? (
              <MessageMarkdown text={content} />
            ) : (
              <pre className={styles.documentPreviewText}>{content}</pre>
            )}
          </>
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={tw('documents.previewEmpty', 'Nothing to preview')}
          />
        )}
      </Spin>
    </Drawer>
  );
}
