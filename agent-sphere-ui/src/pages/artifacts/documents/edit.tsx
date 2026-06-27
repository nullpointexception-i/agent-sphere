import { useParams, history, useIntl } from '@umijs/max';
import { useEffect, useState, useRef } from 'react';
import { Spin, Button, Input, App } from 'antd';
import { ArrowLeftOutlined, BoldOutlined, ItalicOutlined, OrderedListOutlined, UnorderedListOutlined, FontSizeOutlined, CodeOutlined, InsertRowRightOutlined, TableOutlined } from '@ant-design/icons';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Table } from '@tiptap/extension-table';
import { TableRow } from '@tiptap/extension-table-row';
import { TableCell } from '@tiptap/extension-table-cell';
import { TableHeader } from '@tiptap/extension-table-header';
import { Markdown } from 'tiptap-markdown';
import { agentApi } from '@/services/agentSphere/api';

export default function DocumentEdit() {
  const { id } = useParams<{ id: string }>();
  const intl = useIntl();
  const { message } = App.useApp();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [title, setTitle] = useState('');
  const [docContent, setDocContent] = useState<string | null>(null);
  const setContentDone = useRef(false);
  const fetchDone = useRef(false);

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
      }),
      Table.configure({ resizable: true }),
      TableRow,
      TableCell,
      TableHeader,
      Markdown,
    ],
    editorProps: {
      attributes: {
        class: 'tiptap-editor',
        style: 'min-height: 400px; padding: 16px; outline: none;',
      },
    },
  });

  useEffect(() => {
    if (!id || fetchDone.current) return;
    fetchDone.current = true;
    agentApi.artifacts.documents.getById(Number(id))
      .then((doc: any) => {
        setTitle(doc.title || '');
        setDocContent(doc.content || '');
        setLoading(false);
      })
      .catch(() => {
        message.error(intl.formatMessage({ id: 'pages.document.loadFailed', defaultMessage: 'Failed to load document' }));
        setLoading(false);
      });
  }, [id]);

  useEffect(() => {
    if (!editor || !docContent || setContentDone.current) return;
    setContentDone.current = true;
    editor.commands.setContent(docContent);
  }, [editor, docContent]);

  const onSave = async () => {
    if (!id || !editor) return;
    setSaving(true);
    try {
      const content = (editor.storage as any).markdown.getMarkdown();
      await agentApi.artifacts.documents.update(Number(id), title, content);
      message.success(intl.formatMessage({ id: 'pages.document.saveSuccess', defaultMessage: 'Saved' }));
      history.push('/artifacts/documents');
    } catch {
      message.error(intl.formatMessage({ id: 'pages.document.saveFailed', defaultMessage: 'Save failed' }));
    } finally {
      setSaving(false);
    }
  };

  const ToolbarBtn = ({ onClick, icon, title: tooltip, active }: { onClick: () => void; icon: React.ReactNode; title: string; active?: boolean }) => (
    <Button
      type={active ? 'primary' : 'text'}
      size="small"
      icon={icon}
      onClick={onClick}
      title={tooltip}
      style={{ marginRight: 2 }}
    />
  );

  const toggleHeading = (level: 1 | 2 | 3) => {
    if (!editor) return;
    if (editor.isActive('heading', { level })) {
      editor.chain().focus().setParagraph().run();
    } else {
      editor.chain().focus().toggleHeading({ level }).run();
    }
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div style={{ padding: '24px 32px', maxWidth: 1200, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Button
          type="link"
          icon={<ArrowLeftOutlined />}
          onClick={() => history.push('/artifacts/documents')}
          style={{ padding: 0 }}
        >
          {intl.formatMessage({ id: 'pages.document.back', defaultMessage: 'Back' })}
        </Button>
        <Button type="primary" loading={saving} onClick={onSave}>
          {intl.formatMessage({ id: 'pages.document.save', defaultMessage: 'Save' })}
        </Button>
      </div>

      <Input
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        placeholder={intl.formatMessage({ id: 'pages.document.title', defaultMessage: 'Title' })}
        style={{ fontSize: 18, fontWeight: 600, marginBottom: 16, border: 'none', borderBottom: '1px solid #e8e8e8', borderRadius: 0, padding: '8px 0' }}
        variant="borderless"
      />

      <style>{`
        .tiptap-editor table {
          border-collapse: collapse;
          width: 100%;
          margin: 8px 0;
        }
        .tiptap-editor th, .tiptap-editor td {
          border: 1px solid #d9d9d9;
          padding: 6px 10px;
          text-align: left;
          vertical-align: top;
        }
        .tiptap-editor th {
          background: #fafafa;
          font-weight: 600;
        }
        .tiptap-editor p {
          margin: 0;
        }
      `}</style>
      {editor && (
        <div style={{ border: '1px solid #e8e8e8', borderRadius: 6, overflow: 'hidden' }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 2, padding: '6px 8px', borderBottom: '1px solid #e8e8e8', background: '#fafafa' }}>
            <ToolbarBtn onClick={() => editor.chain().focus().toggleBold().run()} icon={<BoldOutlined />} title="Bold" active={editor.isActive('bold')} />
            <ToolbarBtn onClick={() => editor.chain().focus().toggleItalic().run()} icon={<ItalicOutlined />} title="Italic" active={editor.isActive('italic')} />
            <ToolbarBtn onClick={() => toggleHeading(1)} icon={<FontSizeOutlined />} title="Heading 1" active={editor.isActive('heading', { level: 1 })} />
            <ToolbarBtn onClick={() => toggleHeading(2)} icon={<FontSizeOutlined />} title="Heading 2" active={editor.isActive('heading', { level: 2 })} />
            <ToolbarBtn onClick={() => toggleHeading(3)} icon={<FontSizeOutlined />} title="Heading 3" active={editor.isActive('heading', { level: 3 })} />
            <ToolbarBtn onClick={() => editor.chain().focus().toggleBulletList().run()} icon={<UnorderedListOutlined />} title="Bullet List" active={editor.isActive('bulletList')} />
            <ToolbarBtn onClick={() => editor.chain().focus().toggleOrderedList().run()} icon={<OrderedListOutlined />} title="Ordered List" active={editor.isActive('orderedList')} />
            <ToolbarBtn onClick={() => editor.chain().focus().toggleBlockquote().run()} icon={<InsertRowRightOutlined />} title="Blockquote" active={editor.isActive('blockquote')} />
            <ToolbarBtn onClick={() => editor.chain().focus().toggleCodeBlock().run()} icon={<CodeOutlined />} title="Code Block" active={editor.isActive('codeBlock')} />
            <ToolbarBtn onClick={() => editor.chain().focus().insertTable({ rows: 3, cols: 3 }).run()} icon={<TableOutlined />} title="Insert Table" active={editor.isActive('table')} />
          </div>
          <EditorContent editor={editor} />
        </div>
      )}
    </div>
  );
}
