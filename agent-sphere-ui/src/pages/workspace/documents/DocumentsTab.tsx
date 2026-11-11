import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { App, Button, Empty, Input, Segmented, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { DOCUMENT_PAGE_SIZE } from '@/constants/pagination';
import { PERM } from '@/constants/permissions';
import { TIMING } from '@/constants/timing';
import { useCan } from '@/hooks/usePermission';
import type { DocumentSummary } from '@/services/agentSphere/api';
import { agentApi } from '@/services/agentSphere/api';
import { getWebOrigin } from '@/utils/runtimeEnv';
import { tw } from '../i18n';
import { DocumentCard } from './DocumentCard';
import { DocumentEditDrawer } from './DocumentEditDrawer';
import { DocumentPreviewDrawer } from './DocumentPreviewDrawer';
import { documentDownloadName, downloadDocumentText } from './documentModel';
import { useStyles } from './style';

type DocumentScope = 'mine' | 'team';

export function DocumentsTab() {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const canEdit = useCan(PERM.documentUpdate);
  const canShare = useCan(PERM.documentShare);
  const canDelete = useCan(PERM.documentDelete);

  const [scope, setScope] = useState<DocumentScope>('mine');
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [items, setItems] = useState<DocumentSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState(false);
  const [preview, setPreview] = useState<DocumentSummary | null>(null);
  const [editing, setEditing] = useState<DocumentSummary | null>(null);
  const loadSeq = useRef(0);

  useEffect(() => {
    const timer = window.setTimeout(
      () => setSearch(keyword.trim()),
      TIMING.DOCUMENT_SEARCH_DEBOUNCE_MS,
    );
    return () => window.clearTimeout(timer);
  }, [keyword]);

  const fetchPage = async (targetPage: number, append: boolean) => {
    const seq = ++loadSeq.current;
    if (append) {
      setLoadingMore(true);
    } else {
      setLoading(true);
      setError(false);
    }
    try {
      const result = await agentApi.artifacts.documents.list({
        scope,
        page: targetPage,
        size: DOCUMENT_PAGE_SIZE,
        keyword: search || undefined,
      });
      if (seq !== loadSeq.current) return;
      const rows = result?.records || [];
      setItems((previous) => {
        if (!append) return rows;
        const seen = new Set(previous.map((row) => row.id));
        return [...previous, ...rows.filter((row) => !seen.has(row.id))];
      });
      setTotal(result?.total || 0);
      setPage(targetPage);
    } catch {
      if (seq !== loadSeq.current) return;
      if (append) {
        message.error(tw('documents.loadFailed', 'Failed to load documents'));
      } else {
        setError(true);
      }
    } finally {
      if (seq === loadSeq.current) {
        if (append) setLoadingMore(false);
        else setLoading(false);
      }
    }
  };

  useEffect(() => {
    void fetchPage(1, false);
  }, [scope, search]);

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
          if (preview?.id === document.id) setPreview(null);
          await fetchPage(1, false);
        } catch {
          message.error(tw('documents.deleteFailed', 'Failed to delete'));
        }
      },
    });
  };

  const mine = scope === 'mine';

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
            onClick={() => void fetchPage(1, false)}
          >
            {tw('documents.refresh', 'Refresh')}
          </Button>
        </div>
      </div>

      {loading ? (
        <div className={styles.documentsState}>
          <Spin />
        </div>
      ) : error ? (
        <div className={styles.documentsState}>
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={tw('documents.loadFailed', 'Failed to load documents')}
          />
          <Button onClick={() => void fetchPage(1, false)}>
            {tw('documents.retry', 'Retry')}
          </Button>
        </div>
      ) : items.length === 0 ? (
        <div className={styles.documentsState}>
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              mine
                ? tw('documents.emptyMine', 'You have no documents yet')
                : tw('documents.emptyTeam', 'No team documents yet')
            }
          />
        </div>
      ) : (
        <>
          <div className={styles.documentsGrid}>
            {items.map((document) => (
              <DocumentCard
                key={document.id}
                document={document}
                mine={mine}
                canEdit={canEdit}
                canShare={canShare}
                canDelete={canDelete}
                onPreview={() => setPreview(document)}
                onEdit={() => setEditing(document)}
                onExport={() => void exportDocument(document)}
                onShare={() => void shareDocument(document)}
                onDelete={() => deleteDocument(document)}
              />
            ))}
          </div>
          {items.length < total ? (
            <div className={styles.documentsLoadMore}>
              <Button
                loading={loadingMore}
                onClick={() => void fetchPage(page + 1, true)}
              >
                {tw('documents.loadMore', 'Load more')}
              </Button>
            </div>
          ) : null}
        </>
      )}

      <DocumentPreviewDrawer
        open={preview != null}
        document={preview}
        mine={mine}
        canEdit={canEdit}
        onClose={() => setPreview(null)}
        onEdit={() => {
          const current = preview;
          setPreview(null);
          setEditing(current);
        }}
      />
      <DocumentEditDrawer
        open={editing != null}
        document={editing}
        onClose={() => setEditing(null)}
        onSaved={() => void fetchPage(1, false)}
      />
    </div>
  );
}
