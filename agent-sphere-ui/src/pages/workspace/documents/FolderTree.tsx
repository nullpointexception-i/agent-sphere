import {
  DeleteOutlined,
  DownloadOutlined,
  DownOutlined,
  EditOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InboxOutlined,
  MoreOutlined,
  PlusOutlined,
  RightOutlined,
  RobotOutlined,
  ShareAltOutlined,
} from '@ant-design/icons';
import { Button, Dropdown } from 'antd';
import { useMemo, useState } from 'react';
import type {
  DocumentFolder,
  DocumentSummary,
} from '@/services/agentSphere/api';
import { tw } from '../i18n';
import { folderDisplayName } from './folderDisplay';
import { useStyles } from './style';
import { type FolderNode, buildFolderTree } from './wikiModel';

const SYSTEM_INSTANCE_ROOT = 'INSTANCE_ROOT';

interface FolderTreeProps {
  folders: DocumentFolder[];
  documents: DocumentSummary[];
  selectedFolderId: number | null;
  selectedDocumentId: number | null;
  onSelectFolder: (folderId: number | null) => void;
  onSelectDocument: (document: DocumentSummary) => void;
  onCreate: (parentId: number | null) => void;
  onRename: (folder: DocumentFolder) => void;
  onMove: (folder: DocumentFolder) => void;
  onDelete: (folder: DocumentFolder) => void;
  onDocumentAction?: (
    document: DocumentSummary,
    action: 'move' | 'export' | 'share' | 'edit' | 'delete',
  ) => void;
}

/**
 * Left-hand wiki page tree: folders and their pages. Selecting a page opens it on
 * the right; selecting a folder shows its overview.
 */
export function FolderTree({
  folders,
  documents,
  selectedFolderId,
  selectedDocumentId,
  onSelectFolder,
  onSelectDocument,
  onCreate,
  onRename,
  onMove,
  onDelete,
  onDocumentAction,
}: FolderTreeProps) {
  const { styles } = useStyles();
  const tree = useMemo(() => buildFolderTree(folders), [folders]);
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set());

  const docsByFolder = useMemo(() => {
    const map = new Map<number | null, DocumentSummary[]>();
    for (const document of documents) {
      const key = document.folderId ?? null;
      const list = map.get(key) ?? [];
      list.push(document);
      map.set(key, list);
    }
    return map;
  }, [documents]);

  const toggle = (id: number) => {
    setCollapsed((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const renderDocument = (document: DocumentSummary, depth: number) => (
    <Dropdown
      key={`doc-${document.id}`}
      trigger={['contextMenu']}
      menu={{
        items: [
          {
            key: 'move',
            icon: <FolderOutlined />,
            label: tw('documents.move', 'Move'),
          },
          {
            key: 'export',
            icon: <DownloadOutlined />,
            label: tw('documents.export', 'Export'),
          },
          {
            key: 'share',
            icon: <ShareAltOutlined />,
            label: tw('documents.share', 'Share'),
          },
          {
            key: 'edit',
            icon: <EditOutlined />,
            label: tw('documents.edit', 'Edit'),
          },
          { type: 'divider' },
          {
            key: 'delete',
            danger: true,
            icon: <DeleteOutlined />,
            label: tw('documents.delete', 'Delete'),
          },
        ],
        onClick: ({ key, domEvent }) => {
          domEvent.stopPropagation();
          onDocumentAction?.(document, key as 'move' | 'export' | 'share' | 'edit' | 'delete');
        },
      }}
    >
      <div
        className={`${styles.folderRow} ${
          selectedDocumentId === document.id ? styles.folderRowActive : ''
        }`}
        style={{ paddingLeft: 8 + depth * 16 }}
        onClick={() => onSelectDocument(document)}
        title={document.title}
      >
        <span className={styles.folderCarets} />
        <FileTextOutlined className={styles.folderIcon} />
        <span className={styles.folderName}>{document.title || '-'}</span>
        {document.commentCount ? (
          <span className={styles.folderCount}>{document.commentCount}</span>
        ) : null}
      </div>
    </Dropdown>
  );

  const renderNode = (node: FolderNode, depth: number) => {
    const system = node.systemKey === SYSTEM_INSTANCE_ROOT;
    const displayName = folderDisplayName(node);
    const expanded = !collapsed.has(node.id);
    const pages = docsByFolder.get(node.id) ?? [];
    const hasChildren = node.children.length > 0 || pages.length > 0;
    const active = selectedFolderId === node.id && selectedDocumentId == null;
    return (
      <div key={node.id}>
        <div
          className={`${styles.folderRow} ${active ? styles.folderRowActive : ''}`}
          style={{ paddingLeft: 8 + depth * 16 }}
          onClick={() => onSelectFolder(node.id)}
        >
          <span
            className={styles.folderCarets}
            onClick={(event) => {
              event.stopPropagation();
              if (hasChildren) toggle(node.id);
            }}
          >
            {hasChildren ? (
              expanded ? (
                <DownOutlined />
              ) : (
                <RightOutlined />
              )
            ) : null}
          </span>
          {system || node.instanceId ? (
            <RobotOutlined className={styles.folderIcon} />
          ) : active ? (
            <FolderOpenOutlined className={styles.folderIcon} />
          ) : (
            <FolderOutlined className={styles.folderIcon} />
          )}
          <span className={styles.folderName} title={displayName}>
            {displayName}
          </span>
          {system ? (
            <span className={styles.folderHint}>
              {tw('documents.systemFolder', 'Auto')}
            </span>
          ) : node.documentCount ? (
            <span className={styles.folderCount}>{node.documentCount}</span>
          ) : null}
          {!system ? (
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'create',
                    label: tw('documents.newSubfolder', 'New subfolder'),
                  },
                  { key: 'rename', label: tw('documents.rename', 'Rename') },
                  { key: 'move', label: tw('documents.moveTo', 'Move to…') },
                  { type: 'divider' },
                  {
                    key: 'delete',
                    danger: true,
                    label: tw('documents.deleteFolder', 'Delete folder'),
                  },
                ],
                onClick: ({ key, domEvent }) => {
                  domEvent.stopPropagation();
                  if (key === 'create') onCreate(node.id);
                  if (key === 'rename') onRename(node);
                  if (key === 'move') onMove(node);
                  if (key === 'delete') onDelete(node);
                },
              }}
            >
              <Button
                type="text"
                size="small"
                className={styles.folderMore}
                icon={<MoreOutlined />}
                onClick={(event) => event.stopPropagation()}
              />
            </Dropdown>
          ) : null}
        </div>
        {expanded ? (
          <>
            {node.children.map((child) => renderNode(child, depth + 1))}
            {pages.map((document) => renderDocument(document, depth + 1))}
          </>
        ) : null}
      </div>
    );
  };

  const unfiled = docsByFolder.get(null) ?? [];

  return (
    <div className={styles.folderTree}>
      <div
        className={`${styles.folderRow} ${
          selectedFolderId === 0 && selectedDocumentId == null
            ? styles.folderRowActive
            : ''
        }`}
        style={{ paddingLeft: 8 }}
        onClick={() => onSelectFolder(0)}
      >
        <span className={styles.folderCarets} />
        <InboxOutlined className={styles.folderIcon} />
        <span className={styles.folderName}>
          {tw('documents.unfiled', 'Unfiled')}
        </span>
        {unfiled.length ? (
          <span className={styles.folderCount}>{unfiled.length}</span>
        ) : null}
      </div>
      {unfiled.map((document) => renderDocument(document, 1))}
      {tree.map((node) => renderNode(node, 0))}
      <Button
        type="dashed"
        block
        size="small"
        className={styles.folderCreate}
        icon={<PlusOutlined />}
        onClick={() => onCreate(null)}
      >
        {tw('documents.newFolder', 'New folder')}
      </Button>
    </div>
  );
}
