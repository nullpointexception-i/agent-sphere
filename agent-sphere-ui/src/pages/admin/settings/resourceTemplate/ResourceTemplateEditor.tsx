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
  title?: string;
  onClose: () => void;
  onSaved: (jsonText: string) => Promise<void> | void;
}

export default function ResourceTemplateEditor({
  open,
  initialValue,
  title,
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
          defaultMessage:
            'Current config is not valid JSON. Fix it in JSON mode first.',
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
        defaultMessage: 'Form',
      }),
      children: (
        <>
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            {intl.formatMessage({
              id: 'pages.admin.settings.template.tab.formDesc',
              defaultMessage:
                'Visually edit resource template items by type; providers / routes refer to each other by template names.',
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
                  'JSON array format, matching backend config storage; must be valid JSON on save.',
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
      title={
        title ??
        intl.formatMessage({
          id: 'pages.admin.settings.template.title',
          defaultMessage: 'Resource Template',
        })
      }
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
