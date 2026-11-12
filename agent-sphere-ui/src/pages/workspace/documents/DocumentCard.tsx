import {
  CommentOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  LockOutlined,
  MoreOutlined,
  ShareAltOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { Dropdown } from 'antd';
import type { DocumentSummary } from '@/services/agentSphere/api';
import { tw } from '../i18n';
import { DocumentFileIcon } from './DocumentFileIcon';
import {
  documentKind,
  documentUpdatedAt,
  formatDocumentSize,
} from './documentModel';
import { useStyles } from './style';

export function DocumentCard({
  document,
  mine,
  canEdit,
  canShare,
  canDelete,
  onPreview,
  onEdit,
  onExport,
  onShare,
  onDelete,
}: {
  document: DocumentSummary;
  mine: boolean;
  canEdit: boolean;
  canShare: boolean;
  canDelete: boolean;
  onPreview: () => void;
  onEdit: () => void;
  onExport: () => void;
  onShare: () => void;
  onDelete: () => void;
}) {
  const { styles } = useStyles();
  const kind = documentKind(document);
  const size = formatDocumentSize(document.contentLength);
  const updated = documentUpdatedAt(document.updatedAt);

  const items: MenuProps['items'] = [
    {
      key: 'preview',
      icon: <EyeOutlined />,
      label: tw('documents.preview', 'Preview'),
    },
    {
      key: 'export',
      icon: <DownloadOutlined />,
      label: tw('documents.export', 'Export'),
    },
    ...(mine && canEdit
      ? [
          {
            key: 'edit',
            icon: <EditOutlined />,
            label: tw('documents.edit', 'Edit'),
          },
        ]
      : []),
    ...(mine && canShare
      ? [
          {
            key: 'share',
            icon: <ShareAltOutlined />,
            label: tw('documents.share', 'Share'),
          },
        ]
      : []),
    ...(mine && canDelete
      ? [
          { type: 'divider' as const },
          {
            key: 'delete',
            danger: true,
            icon: <DeleteOutlined />,
            label: tw('documents.delete', 'Delete'),
          },
        ]
      : []),
  ];

  const onMenuClick: MenuProps['onClick'] = ({ key, domEvent }) => {
    domEvent.stopPropagation();
    if (key === 'preview') onPreview();
    if (key === 'export') onExport();
    if (key === 'edit') onEdit();
    if (key === 'share') onShare();
    if (key === 'delete') onDelete();
  };

  return (
    <div className={styles.documentCard}>
      <div className={styles.documentCardTop}>
        {!mine ? (
          <span className={styles.documentReadonly}>
            <LockOutlined />
            {tw('documents.readOnly', 'Read-only')}
          </span>
        ) : null}
        <Dropdown trigger={['click']} menu={{ items, onClick: onMenuClick }}>
          <button
            type="button"
            className={styles.documentMore}
            aria-label={tw('documents.actions', 'Document actions')}
          >
            <MoreOutlined />
          </button>
        </Dropdown>
      </div>
      <button
        type="button"
        className={styles.documentCardButton}
        onClick={onPreview}
        title={document.title}
      >
        <span className={styles.documentIconWrap}>
          <DocumentFileIcon kind={kind} />
        </span>
        <span className={styles.documentName}>{document.title || '-'}</span>
        <span className={styles.documentMeta}>
          <span className={styles.documentMetaRow}>
            {document.createdBy
              ? tw('documents.ownerBy', 'By {name}', {
                  name: document.createdBy,
                })
              : tw('documents.unknownOwner', 'Unknown owner')}
          </span>
          <span className={styles.documentMetaRow}>
            {updated}
            {size ? ` · ${size} ${tw('documents.chars', 'chars')}` : ''}
            {document.commentCount ? (
              <span className={styles.documentCommentBadge}>
                {' · '}
                <CommentOutlined /> {document.commentCount}
              </span>
            ) : null}
          </span>
        </span>
      </button>
    </div>
  );
}
