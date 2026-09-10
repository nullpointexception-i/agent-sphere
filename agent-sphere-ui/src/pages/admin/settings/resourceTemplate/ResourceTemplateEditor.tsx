import { useIntl } from '@umijs/max';
import { Alert, App, Button, Drawer, Input, Tabs, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  collectIssues,
  parseTemplate as parseText,
  stringifyTemplate,
} from './serialize';
import TemplateList from './TemplateList';
import type { TemplateItem } from './types';

interface Props {
  open: boolean;
  initialValue: string;
  onClose: () => void;
  onSaved: (jsonText: string) => Promise<void> | void;
}

export default function ResourceTemplateEditor({
  open,
  initialValue,
  onClose,
  onSaved,
}: Props) {
  const intl = useIntl();
  const { message } = App.useApp();
  const [activeTab, setActiveTab] = useState('form');
  const [items, setItems] = useState<TemplateItem[]>([]);
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    try {
      const parsed = parseText(initialValue);
      setItems(parsed);
      setJsonError('');
      setActiveTab('form');
    } catch {
      setItems([]);
      setJsonError(
        intl.formatMessage({
          id: 'pages.admin.settings.template.jsonInvalidOnOpen',
          defaultMessage: '当前配置不是合法 JSON，请先在 JSON 模式下修复',
        }),
      );
      setActiveTab('json');
    }
    setJsonText(initialValue || '[]');
  }, [open, initialValue, intl]);

  const issues = useMemo(() => collectIssues(items), [items]);

  const handleJsonChange = (text: string) => {
    setJsonText(text);
    try {
      const parsed = parseText(text);
      setItems(parsed);
      setJsonError('');
    } catch (e) {
      setJsonError((e as Error).message);
    }
  };

  const handleItemsChange = (next: TemplateItem[]) => {
    setItems(next);
    const text = stringifyTemplate(next);
    setJsonText(text);
    setJsonError('');
  };

  const handleSwitch = (key: string) => {
    if (key === 'form' && jsonError) {
      message.error(jsonError);
      return;
    }
    setActiveTab(key);
  };

  const handleSave = useCallback(async () => {
    if (jsonError) {
      message.error(jsonError);
      return;
    }
    setSaving(true);
    try {
      await onSaved(jsonText);
      message.success(intl.formatMessage({ id: 'pages.save.success' }));
    } catch {
      message.error(intl.formatMessage({ id: 'pages.chat.saveFailed' }));
    } finally {
      setSaving(false);
    }
  }, [jsonError, jsonText, onSaved, intl, message]);

  const tabItems = [
    {
      key: 'form',
      label: intl.formatMessage({
        id: 'pages.admin.settings.template.tab.form',
        defaultMessage: '页面配置',
      }),
      children: (
        <>
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            {intl.formatMessage({
              id: 'pages.admin.settings.template.tab.formDesc',
              defaultMessage:
                '按类型可视化编辑资源配置项；模型供应商 / 路由通过模板内的名称互相引用。',
            })}
          </Typography.Paragraph>
          <TemplateList
            items={items}
            issues={issues}
            onChange={handleItemsChange}
          />
        </>
      ),
    },
    {
      key: 'json',
      label: intl.formatMessage({
        id: 'pages.admin.settings.template.tab.json',
        defaultMessage: 'JSON',
      }),
      children: (
        <>
          {jsonError ? (
            <Alert
              style={{ margin: '8px 0' }}
              type="error"
              showIcon
              title={jsonError}
            />
          ) : (
            <Alert
              style={{ margin: '8px 0' }}
              type="info"
              showIcon
              title={intl.formatMessage({
                id: 'pages.admin.settings.template.tab.jsonDesc',
                defaultMessage:
                  'JSON 数组格式，与后端配置存储一致；保存时需为合法 JSON。',
              })}
            />
          )}
          <Input.TextArea
            value={jsonText}
            onChange={(e) => handleJsonChange(e.target.value)}
            rows={18}
            spellCheck={false}
            style={{ fontFamily: 'monospace', fontSize: 13 }}
          />
        </>
      ),
    },
  ];

  return (
    <Drawer
      title={intl.formatMessage({
        id: 'pages.admin.settings.template.title',
        defaultMessage: '资源配置模板',
      })}
      size={900}
      open={open}
      onClose={onClose}
      destroyOnHidden
      styles={{ body: { paddingTop: 8 } }}
      extra={
        <>
          <Button style={{ marginRight: 8 }} onClick={onClose}>
            {intl.formatMessage({ id: 'pages.cancel' })}
          </Button>
          <Button type="primary" loading={saving} onClick={handleSave}>
            {intl.formatMessage({ id: 'pages.save' })}
          </Button>
        </>
      }
    >
      <Tabs activeKey={activeTab} onChange={handleSwitch} items={tabItems} />
    </Drawer>
  );
}
