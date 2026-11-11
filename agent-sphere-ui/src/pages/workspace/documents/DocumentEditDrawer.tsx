import { App, Button, Drawer, Input, Space, Spin } from 'antd';
import { useEffect, useState } from 'react';
import type { DocumentSummary } from '@/services/agentSphere/api';
import { agentApi } from '@/services/agentSphere/api';
import { tw } from '../i18n';

export function DocumentEditDrawer({
  open,
  document,
  onClose,
  onSaved,
}: {
  open: boolean;
  document: DocumentSummary | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { message } = App.useApp();
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open || !document) {
      setTitle('');
      setContent('');
      return;
    }
    let active = true;
    setLoading(true);
    agentApi.artifacts.documents
      .getById(document.id)
      .then((result: any) => {
        if (!active) return;
        setTitle(result?.title || document.title || '');
        setContent(result?.content || '');
      })
      .catch(() => {
        if (active) {
          message.error(tw('documents.loadFailed', 'Failed to load document'));
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [open, document?.id]);

  const save = async () => {
    if (!document || saving) return;
    if (!title.trim()) {
      message.warning(tw('documents.titleRequired', 'Title is required'));
      return;
    }
    setSaving(true);
    try {
      await agentApi.artifacts.documents.update(
        document.id,
        title.trim(),
        content,
      );
      message.success(tw('documents.saved', 'Document saved'));
      onSaved();
      onClose();
    } catch (error: any) {
      message.error(
        error?.response?.data?.message ||
          error?.data?.message ||
          error?.message ||
          tw('documents.saveFailed', 'Failed to save document'),
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={760}
      title={tw('documents.editTitle', 'Edit document')}
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>{tw('common.cancel', 'Cancel')}</Button>
          <Button type="primary" loading={saving} onClick={save}>
            {tw('common.save', 'Save')}
          </Button>
        </Space>
      }
    >
      <Spin spinning={loading}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={tw('documents.titlePlaceholder', 'Document title')}
          />
          <Input.TextArea
            value={content}
            onChange={(event) => setContent(event.target.value)}
            rows={20}
            placeholder={tw('documents.contentPlaceholder', 'Markdown content')}
          />
        </Space>
      </Spin>
    </Drawer>
  );
}
