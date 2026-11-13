import {
  ArrowLeftOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Empty,
  Input,
  Modal,
  Radio,
  Segmented,
  Select,
  Spin,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { DOCUMENT_PAGE_SIZE } from '@/constants/pagination';
import { PERM } from '@/constants/permissions';
import { TIMING } from '@/constants/timing';
import { useCan } from '@/hooks/usePermission';
import type {
  DocumentFolder,
  DocumentSummary,
} from '@/services/agentSphere/api';
import { agentApi } from '@/services/agentSphere/api';
import { getWebOrigin } from '@/utils/runtimeEnv';
import { tw } from '../i18n';
import { DocumentCard } from './DocumentCard';
import { DocumentEditDrawer } from './DocumentEditDrawer';
import { documentDownloadName, downloadDocumentText } from './documentModel';
import { FolderTree } from './FolderTree';
import { folderDisplayName } from './folderDisplay';
import { useStyles } from './style';
import { WikiPageView } from './WikiPageView';
import { folderMoveOptions, folderPath } from './wikiModel';

type DocumentScope = 'mine' | 'team';

const UNFILED_FOLDER_ID = 0;
const ALL_DOCUMENTS_PAGE_SIZE = 200;
const ALL_DOCUMENTS_MAX_PAGES = 20;

export function DocumentsTab() {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const _canEdit = useCan(PERM.documentUpdate);
  const _canShare = useCan(PERM.documentShare);
  const _canDelete = useCan(PERM.documentDelete);

  const [scope, setScope] = useState<DocumentScope>('mine');
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [folders, setFolders] = useState<DocumentFolder[]>([]);
  const [documents, setDocuments] = useState<DocumentSummary[]>([]);
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [documentsError, setDocumentsError] = useState(false);
  const [selectedFolderId, setSelectedFolderId] = useState<number | null>(null);
  const [selectedDocument, setSelectedDocument] =
    useState<DocumentSummary | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);

  const [teamItems, setTeamItems] = useState<DocumentSummary[]>([]);
  const [teamTotal, setTeamTotal] = useState(0);
  const [teamPage, setTeamPage] = useState(1);
  const [teamLoading, setTeamLoading] = useState(false);
  const [teamLoadingMore, setTeamLoadingMore] = useState(false);
  const [teamError, setTeamError] = useState(false);

  const [editing, setEditing] = useState<DocumentSummary | null>(null);
  const [folderModal, setFolderModal] = useState<{
    mode: 'create' | 'rename';
    folder?: DocumentFolder;
    parentId: number | null;
    name: string;
  } | null>(null);
  const [folderMove, setFolderMove] = useState<DocumentFolder | null>(null);
  const [folderMoveParent, setFolderMoveParent] = useState<number>(0);
  const [movingDocument, setMovingDocument] = useState<DocumentSummary | null>(
    null,
  );
  const [documentTargetFolder, setDocumentTargetFolder] = useState<number>(0);
  const loadSeq = useRef(0);
  const mine = scope === 'mine';

  useEffect(() => {
    const timer = window.setTimeout(
      () => setSearch(keyword.trim()),
      TIMING.DOCUMENT_SEARCH_DEBOUNCE_MS,
    );
    return () => window.clearTimeout(timer);
  }, [keyword]);

  const loadFolders = async () => {
    try {
      setFolders((await agentApi.artifacts.documents.folders()) || []);
    } catch {
      setFolders([]);
    }
  };

  const loadMineDocuments = async () => {
    const seq = ++loadSeq.current;
    setDocumentsLoading(true);
    setDocumentsError(false);
    try {
      const collected: DocumentSummary[] = [];
      let total = Number.MAX_SAFE_INTEGER;
      for (
        let page = 1;
        page <= ALL_DOCUMENTS_MAX_PAGES && collected.length < total;
        page += 1
      ) {
        const result = await agentApi.artifacts.documents.list({
          scope: 'mine',
          page,
          size: ALL_DOCUMENTS_PAGE_SIZE,
          keyword: search || undefined,
        });
        const rows = result?.records || [];
        collected.push(...rows);
        total = result?.total || 0;
        if (rows.length < ALL_DOCUMENTS_PAGE_SIZE) break;
      }
      if (seq !== loadSeq.current) return;
      const seen = new Set<number>();
      const unique = collected.filter((row) => {
        if (seen.has(row.id)) return false;
        seen.add(row.id);
        return true;
      });
      setDocuments(unique);
      setSelectedDocument((previous) =>
        previous
          ? (unique.find((row) => row.id === previous.id) ?? null)
          : null,
      );
    } catch {
      if (seq === loadSeq.current) setDocumentsError(true);
    } finally {
      if (seq === loadSeq.current) setDocumentsLoading(false);
    }
  };

  const fetchTeamPage = async (targetPage: number, append: boolean) => {
    const seq = ++loadSeq.current;
    if (append) setTeamLoadingMore(true);
    else {
      setTeamLoading(true);
      setTeamError(false);
    }
    try {
      const result = await agentApi.artifacts.documents.list({
        scope: 'team',
        page: targetPage,
        size: DOCUMENT_PAGE_SIZE,
        keyword: search || undefined,
      });
      if (seq !== loadSeq.current) return;
      const rows = result?.records || [];
      setTeamItems((previous) => {
        if (!append) return rows;
        const seen = new Set(previous.map((row) => row.id));
        return [...previous, ...rows.filter((row) => !seen.has(row.id))];
      });
      setTeamTotal(result?.total || 0);
      setTeamPage(targetPage);
    } catch {
      if (seq !== loadSeq.current) return;
      if (append) {
        message.error(tw('documents.loadFailed', 'Failed to load documents'));
      } else {
        setTeamError(true);
      }
    } finally {
      if (seq === loadSeq.current) {
        if (append) setTeamLoadingMore(false);
        else setTeamLoading(false);
      }
    }
  };

  useEffect(() => {
    if (mine) void loadMineDocuments();
    else void fetchTeamPage(1, false);
  }, [scope, search]);

  useEffect(() => {
    void loadFolders();
  }, []);

  useEffect(() => {
    if (scope === 'team') {
      setSelectedFolderId(null);
      setSelectedDocument(null);
    }
  }, [scope]);

  const folderOptions = useMemo(() => {
    const options = folderMoveOptions(folders);
    return [
      { value: 0, label: tw('documents.unfiled', 'Unfiled'), depth: 0 },
      ...options.map((option) => ({
        value: option.value,
        label: `${'　'.repeat(option.depth)}${option.label}`,
        depth: option.depth,
      })),
    ];
  }, [folders]);

  const folderMoveTargets = useMemo(() => {
    if (!folderMove) return [];
    const options = folderMoveOptions(folders, folderMove.id);
    return [
      { value: 0, label: tw('documents.rootFolder', 'Root'), depth: 0 },
      ...options.map((option) => ({
        value: option.value,
        label: `${'　'.repeat(option.depth)}${option.label}`,
        depth: option.depth,
      })),
    ];
  }, [folders, folderMove]);

  const breadcrumbFolderId = selectedDocument
    ? (selectedDocument.folderId ?? UNFILED_FOLDER_ID)
    : selectedFolderId;

  const breadcrumb = useMemo(() => {
    if (!mine) return [];
    if (breadcrumbFolderId === UNFILED_FOLDER_ID) {
      return [tw('documents.unfiled', 'Unfiled')];
    }
    return folderPath(folders, breadcrumbFolderId).map((folder) =>
      folderDisplayName(folder),
    );
  }, [folders, breadcrumbFolderId, mine]);

  const refreshAll = async () => {
    await Promise.all([loadFolders(), loadMineDocuments()]);
    setRefreshToken((value) => value + 1);
  };

  const submitFolderModal = async () => {
    if (!folderModal) return;
    const name = folderModal.name.trim();
    if (!name) {
      message.warning(
        tw('documents.folderNameRequired', 'Enter a folder name'),
      );
      return;
    }
    try {
      if (folderModal.mode === 'create') {
        await agentApi.artifacts.documents.createFolder({
          name,
          parentId: folderModal.parentId,
        });
        message.success(tw('documents.folderCreated', 'Folder created'));
      } else if (folderModal.folder) {
        await agentApi.artifacts.documents.updateFolder(folderModal.folder.id, {
          name,
        });
        message.success(tw('documents.folderRenamed', 'Folder renamed'));
      }
      setFolderModal(null);
      await loadFolders();
    } catch {
      message.error(tw('documents.folderSaveFailed', 'Failed to save folder'));
    }
  };

  const deleteFolder = (folder: DocumentFolder) => {
    let mode: 'move' | 'purge' = 'move';
    modal.confirm({
      title: tw('documents.deleteFolderTitle', 'Delete folder "{name}"?', {
        name: folder.name,
      }),
      width: 460,
      content: (
        <div>
          <div style={{ marginBottom: 10 }}>
            {tw(
              'documents.deleteFolderHint',
              'Choose what to do with the documents inside.',
            )}
          </div>
          <Radio.Group
            defaultValue="move"
            onChange={(event) => {
              mode = event.target.value;
            }}
          >
            <Radio value="move" style={{ display: 'block', marginBottom: 8 }}>
              {tw(
                'documents.deleteFolderMove',
                'Delete the folder only; move contents to its parent',
              )}
            </Radio>
            <Radio value="purge" style={{ display: 'block' }}>
              {tw(
                'documents.deleteFolderPurge',
                'Delete the folder and all documents inside (cannot be undone)',
              )}
            </Radio>
          </Radio.Group>
        </div>
      ),
      okButtonProps: { danger: true },
      okText: tw('documents.delete', 'Delete'),
      onOk: async () => {
        try {
          await agentApi.artifacts.documents.deleteFolder(folder.id, mode);
          message.success(tw('documents.folderDeleted', 'Folder deleted'));
          if (selectedFolderId === folder.id) setSelectedFolderId(null);
          await refreshAll();
        } catch {
          message.error(
            tw('documents.folderDeleteFailed', 'Failed to delete folder'),
          );
        }
      },
    });
  };

  const submitFolderMove = async () => {
    if (!folderMove) return;
    try {
      await agentApi.artifacts.documents.updateFolder(folderMove.id, {
        moveToRoot: folderMoveParent === 0,
        ...(folderMoveParent === 0 ? {} : { parentId: folderMoveParent }),
      });
      message.success(tw('documents.folderMoved', 'Folder moved'));
      setFolderMove(null);
      await loadFolders();
    } catch {
      message.error(tw('documents.folderMoveFailed', 'Failed to move folder'));
    }
  };

  const exportDocument = async (document: DocumentSummary) => {
    try {
      const detail: any = await agentApi.artifacts.documents.getById(
        document.id,
      );
      downloadDocumentText(
        documentDownloadName(document),
        detail?.content || '',
      );
    } catch {
      message.error(tw('documents.loadFailed', 'Failed to load documents'));
    }
  };

  const shareDocument = async (document: DocumentSummary) => {
    try {
      const result = await agentApi.artifacts.documents.createShare(
        document.id,
      );
      const origin = getWebOrigin() || window.location.origin;
      const url = `${origin}/s/${result.shareToken}`;
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(url);
        message.success(tw('documents.shareCopied', 'Share link copied'));
      } else {
        message.info(url);
      }
    } catch {
      message.error(tw('documents.shareFailed', 'Failed to create share link'));
    }
  };

  const deleteDocument = (document: DocumentSummary) => {
    modal.confirm({
      title: tw('documents.deleteConfirm', 'Delete this document?'),
      content: document.title,
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await agentApi.artifacts.documents.delete(document.id);
          message.success(tw('documents.deleted', 'Document deleted'));
          if (selectedDocument?.id === document.id) setSelectedDocument(null);
          await refreshAll();
        } catch {
          message.error(tw('documents.deleteFailed', 'Failed to delete'));
        }
      },
    });
  };

  const submitDocumentMove = async () => {
    if (!movingDocument) return;
    try {
      await agentApi.artifacts.documents.moveDocument(
        movingDocument.id,
        documentTargetFolder === 0 ? null : documentTargetFolder,
      );
      message.success(tw('documents.documentMoved', 'Document moved'));
      setMovingDocument(null);
      await refreshAll();
    } catch {
      message.error(
        tw('documents.documentMoveFailed', 'Failed to move document'),
      );
    }
  };

  const selectDocument = (document: DocumentSummary) => {
    setSelectedDocument(document);
    setSelectedFolderId(document.folderId ?? UNFILED_FOLDER_ID);
  };

  const folderDocuments = useMemo(() => {
    if (selectedFolderId == null) return [];
    if (selectedFolderId === UNFILED_FOLDER_ID) {
      return documents.filter((document) => document.folderId == null);
    }
    return documents.filter(
      (document) => document.folderId === selectedFolderId,
    );
  }, [documents, selectedFolderId]);

  const selectedFolderName =
    selectedFolderId === UNFILED_FOLDER_ID
      ? tw('documents.unfiled', 'Unfiled')
      : (() => {
          const folder = folders.find((item) => item.id === selectedFolderId);
          return folder ? folderDisplayName(folder) : undefined;
        })();

  const renderMinePane = () => {
    if (documentsLoading && documents.length === 0) {
      return (
        <div className={styles.documentsState}>
          <Spin />
        </div>
      );
    }
    if (documentsError) {
      return (
        <div className={styles.documentsState}>
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={tw('documents.loadFailed', 'Failed to load documents')}
          />
          <Button onClick={() => void loadMineDocuments()}>
            {tw('documents.retry', 'Retry')}
          </Button>
        </div>
      );
    }
    if (search) {
      return (
        <div className={styles.wikiFolderOverview}>
          <div className={styles.wikiPageTitle}>
            {tw('documents.searchResults', 'Results for "{keyword}"', {
              keyword: search,
            })}
          </div>
          {documents.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={tw('documents.searchEmpty', 'No matching pages')}
            />
          ) : (
            <div className={styles.wikiPageList}>
              {documents.map((document) => (
                <button
                  key={document.id}
                  type="button"
                  className={styles.wikiPageListItem}
                  onClick={() => {
                    setSelectedDocument(document);
                    setSelectedFolderId(document.folderId ?? UNFILED_FOLDER_ID);
                    setKeyword('');
                    setSearch('');
                  }}
                >
                  <span>{document.title || '-'}</span>
                  <span className={styles.wikiPageListMeta}>
                    {document.createdBy} ·{' '}
                    {document.updatedAt?.replace('T', ' ').slice(0, 16)}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      );
    }
    if (selectedDocument) {
      return (
        <WikiPageView
          key={selectedDocument.id}
          document={selectedDocument}
          mine
          refreshToken={refreshToken}
        />
      );
    }
    if (selectedFolderId != null) {
      return (
        <div className={styles.wikiFolderOverview}>
          <div className={styles.wikiPageTitle}>
            {selectedFolderName || '-'}
          </div>
          {folderDocuments.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={tw(
                'documents.emptyFolder',
                'No pages in this folder',
              )}
            />
          ) : (
            <div className={styles.wikiPageList}>
              {folderDocuments.map((document) => (
                <button
                  key={document.id}
                  type="button"
                  className={styles.wikiPageListItem}
                  onClick={() => selectDocument(document)}
                >
                  <span>{document.title || '-'}</span>
                  <span className={styles.wikiPageListMeta}>
                    {document.updatedAt?.replace('T', ' ').slice(0, 16)}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      );
    }
    return (
      <div className={styles.documentsState}>
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={tw(
            'documents.selectPage',
            'Select a page from the left to start reading',
          )}
        />
      </div>
    );
  };

  return (
    <div className={styles.documentsPage}>
      <div className={styles.documentsHeader}>
        <div>
          <div className={styles.documentsTitle}>
            {tw('documents.title', 'Documents')}
          </div>
          <div className={styles.documentsSubtitle}>
            {mine
              ? tw(
                  'documents.mineSubtitle',
                  'Documents you created. You can edit them.',
                )
              : tw(
                  'documents.teamSubtitle',
                  'Tenant documents from other members. Read-only.',
                )}
          </div>
        </div>
        <div className={styles.documentsToolbar}>
          <Segmented
            value={scope}
            options={[
              { value: 'mine', label: tw('documents.mine', 'My documents') },
              { value: 'team', label: tw('documents.team', 'Team documents') },
            ]}
            onChange={(value) => setScope(value as DocumentScope)}
          />
          <Input
            allowClear
            className={styles.documentsSearch}
            value={keyword}
            prefix={<SearchOutlined />}
            placeholder={tw('documents.searchPlaceholder', 'Search documents')}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={() => void (mine ? refreshAll() : fetchTeamPage(1, false))}
          >
            {tw('documents.refresh', 'Refresh')}
          </Button>
        </div>
      </div>

      <div className={styles.documentsBody}>
        {mine ? (
          <FolderTree
            folders={folders}
            documents={documents}
            selectedFolderId={selectedFolderId}
            selectedDocumentId={selectedDocument?.id ?? null}
            onSelectFolder={(folderId) => {
              setSelectedFolderId(folderId);
              setSelectedDocument(null);
            }}
            onSelectDocument={selectDocument}
            onCreate={(parentId) =>
              setFolderModal({ mode: 'create', parentId, name: '' })
            }
            onRename={(folder) =>
              setFolderModal({
                mode: 'rename',
                folder,
                parentId: folder.parentId ?? null,
                name: folder.name,
              })
            }
            onMove={(folder) => {
              setFolderMove(folder);
              setFolderMoveParent(folder.parentId ?? 0);
            }}
            onDelete={deleteFolder}
            onDocumentAction={(document, action) => {
              if (action === 'move') {
                setMovingDocument(document);
                setDocumentTargetFolder(document.folderId ?? UNFILED_FOLDER_ID);
              }
              if (action === 'export') void exportDocument(document);
              if (action === 'share') void shareDocument(document);
              if (action === 'edit') setEditing(document);
              if (action === 'delete') deleteDocument(document);
            }}
          />
        ) : null}

        <div className={styles.wikiContent}>
          {mine ? (
            <div className={styles.wikiBreadcrumb}>
              <span>{tw('documents.mine', 'My documents')}</span>
              {breadcrumb.map((name) => (
                <span key={name}>/ {name}</span>
              ))}
            </div>
          ) : null}

          {!mine ? (
            selectedDocument ? (
              <>
                <div className={styles.wikiBreadcrumb}>
                  <Button
                    type="link"
                    size="small"
                    icon={<ArrowLeftOutlined />}
                    style={{ padding: 0 }}
                    onClick={() => setSelectedDocument(null)}
                  >
                    {tw('documents.team', 'Team documents')}
                  </Button>
                </div>
                <WikiPageView
                  key={selectedDocument.id}
                  document={selectedDocument}
                  mine={false}
                  refreshToken={refreshToken}
                />
              </>
            ) : teamLoading ? (
              <div className={styles.documentsState}>
                <Spin />
              </div>
            ) : teamError ? (
              <div className={styles.documentsState}>
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={tw(
                    'documents.loadFailed',
                    'Failed to load documents',
                  )}
                />
                <Button onClick={() => void fetchTeamPage(1, false)}>
                  {tw('documents.retry', 'Retry')}
                </Button>
              </div>
            ) : teamItems.length === 0 ? (
              <div className={styles.documentsState}>
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={tw(
                    'documents.emptyTeam',
                    'No team documents yet',
                  )}
                />
              </div>
            ) : (
              <>
                <div className={styles.documentsGrid}>
                  {teamItems.map((document) => (
                    <DocumentCard
                      key={document.id}
                      document={document}
                      mine={false}
                      canEdit={false}
                      canShare={false}
                      canDelete={false}
                      onPreview={() => selectDocument(document)}
                      onEdit={() => undefined}
                      onExport={() => void exportDocument(document)}
                      onShare={() => undefined}
                      onDelete={() => undefined}
                    />
                  ))}
                </div>
                {teamItems.length < teamTotal ? (
                  <div className={styles.documentsLoadMore}>
                    <Button
                      loading={teamLoadingMore}
                      onClick={() => void fetchTeamPage(teamPage + 1, true)}
                    >
                      {tw('documents.loadMore', 'Load more')}
                    </Button>
                  </div>
                ) : null}
              </>
            )
          ) : (
            renderMinePane()
          )}
        </div>
      </div>

      <DocumentEditDrawer
        open={editing != null}
        document={editing}
        onClose={() => setEditing(null)}
        onSaved={() => void refreshAll()}
      />

      <Modal
        open={folderModal != null}
        title={
          folderModal?.mode === 'rename'
            ? tw('documents.renameFolder', 'Rename folder')
            : tw('documents.newFolder', 'New folder')
        }
        okText={tw('common.save', 'Save')}
        cancelText={tw('common.cancel', 'Cancel')}
        onOk={() => void submitFolderModal()}
        onCancel={() => setFolderModal(null)}
        destroyOnHidden
      >
        <Input
          autoFocus
          value={folderModal?.name ?? ''}
          maxLength={128}
          placeholder={tw('documents.folderName', 'Folder name')}
          onChange={(event) =>
            setFolderModal((previous) =>
              previous ? { ...previous, name: event.target.value } : previous,
            )
          }
          onPressEnter={() => void submitFolderModal()}
        />
      </Modal>

      <Modal
        open={folderMove != null}
        title={tw('documents.moveFolder', 'Move folder')}
        okText={tw('documents.move', 'Move')}
        cancelText={tw('common.cancel', 'Cancel')}
        onOk={() => void submitFolderMove()}
        onCancel={() => setFolderMove(null)}
        destroyOnHidden
      >
        <Select
          style={{ width: '100%' }}
          value={folderMoveParent}
          options={folderMoveTargets}
          onChange={setFolderMoveParent}
        />
      </Modal>

      <Modal
        open={movingDocument != null}
        title={tw('documents.moveDocument', 'Move document')}
        okText={tw('documents.move', 'Move')}
        cancelText={tw('common.cancel', 'Cancel')}
        onOk={() => void submitDocumentMove()}
        onCancel={() => setMovingDocument(null)}
        destroyOnHidden
      >
        <Select
          style={{ width: '100%' }}
          value={documentTargetFolder}
          options={folderOptions}
          onChange={setDocumentTargetFolder}
        />
      </Modal>
    </div>
  );
}
