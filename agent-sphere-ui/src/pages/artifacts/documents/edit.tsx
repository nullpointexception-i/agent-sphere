import {
  ArrowLeftOutlined,
  BoldOutlined,
  CodeOutlined,
  FontSizeOutlined,
  InsertRowRightOutlined,
  ItalicOutlined,
  MenuOutlined,
  OrderedListOutlined,
  TableOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Table } from '@tiptap/extension-table';
import { TableCell } from '@tiptap/extension-table-cell';
import { TableHeader } from '@tiptap/extension-table-header';
import { TableRow } from '@tiptap/extension-table-row';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { history, useIntl, useParams } from '@umijs/max';
import { App, Button, Input, Spin } from 'antd';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { Markdown } from 'tiptap-markdown';
import { agentApi } from '@/services/agentSphere/api';

interface TocItem {
  level: number;
  text: string;
  pos: number;
}

function TocPanel({
  items,
  onJump,
  visible,
  onClose,
}: {
  items: TocItem[];
  onJump: (pos: number) => void;
  visible: boolean;
  onClose: () => void;
}) {
  if (!visible) return null;
  return (
    <div
      style={{
        width: 200,
        flexShrink: 0,
        borderLeft: '1px solid #e8e8e8',
        padding: '12px 0',
        overflowY: 'auto',
        maxHeight: 'calc(100vh - 280px)',
        position: 'sticky',
        top: 16,
        alignSelf: 'flex-start',
        background: '#fafafa',
      }}
    >
      <div
        style={{
          padding: '0 12px 8px',
          fontSize: 12,
          color: '#999',
          display: 'flex',
          justifyContent: 'space-between',
        }}
      >
        <span>Outline</span>
        <a onClick={onClose} style={{ cursor: 'pointer' }}>
          ✕
        </a>
      </div>
      {items.length === 0 && (
        <div style={{ padding: '0 12px', fontSize: 12, color: '#ccc' }}>
          No headings
        </div>
      )}
      {items.map((item, i) => (
        <div
          key={i}
          onClick={() => onJump(item.pos)}
          style={{
            padding: '4px 12px 4px ' + (12 + (item.level - 1) * 16) + 'px',
            fontSize: 13,
            cursor: 'pointer',
            color: '#333',
            lineHeight: 1.6,
            borderLeft: item.level === 1 ? '2px solid #1677ff' : undefined,
          }}
          onMouseEnter={(e) => {
            (e.target as HTMLElement).style.background = '#e6f4ff';
          }}
          onMouseLeave={(e) => {
            (e.target as HTMLElement).style.background = '';
          }}
        >
          {item.text}
        </div>
      ))}
    </div>
  );
}

export default function DocumentEdit() {
  const { id } = useParams<{ id: string }>();
  const intl = useIntl();
  const { message } = App.useApp();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [title, setTitle] = useState('');
  const [docContent, setDocContent] = useState<string | null>(null);
  const [tocItems, setTocItems] = useState<TocItem[]>([]);
  const [tocOpen, setTocOpen] = useState(true);
  const [containerHeight, setContainerHeight] = useState('100vh');
  const containerRef = useRef<HTMLDivElement>(null);
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
      },
    },
  });

  const updateToc = useCallback(() => {
    if (!editor) return;
    const items: TocItem[] = [];
    editor.state.doc.descendants((node, pos) => {
      if (node.type.name === 'heading') {
        items.push({ level: node.attrs.level, text: node.textContent, pos });
      }
    });
    setTocItems(items);
  }, [editor]);

  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const measure = () => {
      const rect = el.getBoundingClientRect();
      setContainerHeight(`${window.innerHeight - rect.top}px`);
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, []);

  useEffect(() => {
    if (!id || fetchDone.current) return;
    fetchDone.current = true;
    agentApi.artifacts.documents
      .getById(Number(id))
      .then((doc: any) => {
        setTitle(doc.title || '');
        setDocContent(doc.content || '');
        setLoading(false);
      })
      .catch(() => {
        message.error(
          intl.formatMessage({
            id: 'pages.document.loadFailed',
            defaultMessage: 'Failed to load document',
          }),
        );
        setLoading(false);
      });
  }, [id]);

  useEffect(() => {
    if (!editor || !docContent || setContentDone.current) return;
    setContentDone.current = true;
    editor.commands.setContent(docContent);
    setTimeout(() => updateToc(), 100);
  }, [editor, docContent]);

  const onSave = async () => {
    if (!id || !editor) return;
    setSaving(true);
    try {
      const content = (editor.storage as any).markdown.getMarkdown();
      await agentApi.artifacts.documents.update(Number(id), title, content);
      message.success(
        intl.formatMessage({
          id: 'pages.document.saveSuccess',
          defaultMessage: 'Saved',
        }),
      );
      history.push('/artifacts/documents');
    } catch {
      message.error(
        intl.formatMessage({
          id: 'pages.document.saveFailed',
          defaultMessage: 'Save failed',
        }),
      );
    } finally {
      setSaving(false);
    }
  };

  const jumpToHeading = (pos: number) => {
    if (!editor) return;
    editor.chain().focus().setTextSelection(pos).run();
    const dom = editor.view.nodeDOM(pos);
    if (dom instanceof HTMLElement)
      dom.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  const ToolbarBtn = ({
    onClick,
    icon,
    title: tooltip,
    active,
  }: {
    onClick: () => void;
    icon: React.ReactNode;
    title: string;
    active?: boolean;
  }) => (
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
    setTimeout(() => updateToc(), 50);
  };

  return (
    <div
      ref={containerRef}
      style={{
        height: containerHeight,
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        maxWidth: 1400,
        margin: '0 auto',
        padding: '0 32px 16px',
      }}
    >
      {loading ? (
        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            flex: 1,
          }}
        >
          <Spin size="large" />
        </div>
      ) : (
        <>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              paddingTop: 24,
              paddingBottom: 8,
            }}
          >
            <Button
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/artifacts/documents')}
              style={{ padding: 0 }}
            >
              {intl.formatMessage({
                id: 'pages.document.back',
                defaultMessage: 'Back',
              })}
            </Button>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <Button
                icon={<MenuOutlined />}
                type="default"
                size="small"
                onClick={() => setTocOpen(!tocOpen)}
              >
                Outline
              </Button>
              <Button
                type="primary"
                size="small"
                loading={saving}
                onClick={onSave}
              >
                {intl.formatMessage({
                  id: 'pages.document.save',
                  defaultMessage: 'Save',
                })}
              </Button>
            </div>
          </div>

          <Input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder={intl.formatMessage({
              id: 'pages.document.title',
              defaultMessage: 'Title',
            })}
            style={{
              fontSize: 18,
              fontWeight: 600,
              flexShrink: 0,
              border: 'none',
              borderBottom: '1px solid #e8e8e8',
              borderRadius: 0,
              padding: '8px 0 12px',
            }}
            variant="borderless"
          />

          <style>{`
        .tiptap-editor {
          min-height: 400px;
          padding: 16px;
          outline: none;
        }
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
        .tiptap-editor p { margin: 0; }
      `}</style>

          {editor && (
            <div
              style={{
                flex: 1,
                overflow: 'hidden',
                display: 'flex',
                gap: 0,
                border: '1px solid #e8e8e8',
                borderRadius: 6,
              }}
            >
              <div
                style={{
                  flex: 1,
                  display: 'flex',
                  flexDirection: 'column',
                  minWidth: 0,
                  overflow: 'hidden',
                }}
              >
                <div
                  style={{
                    position: 'sticky',
                    top: 0,
                    zIndex: 10,
                    background: '#fafafa',
                    borderBottom: '1px solid #e8e8e8',
                    borderTopLeftRadius: 6,
                    display: 'flex',
                    flexWrap: 'wrap',
                    gap: 2,
                    padding: '6px 8px',
                    flexShrink: 0,
                  }}
                >
                  <ToolbarBtn
                    onClick={() => {
                      editor.chain().focus().toggleBold().run();
                    }}
                    icon={<BoldOutlined />}
                    title="Bold"
                    active={editor.isActive('bold')}
                  />
                  <ToolbarBtn
                    onClick={() => {
                      editor.chain().focus().toggleItalic().run();
                    }}
                    icon={<ItalicOutlined />}
                    title="Italic"
                    active={editor.isActive('italic')}
                  />
                  <ToolbarBtn
                    onClick={() => toggleHeading(1)}
                    icon={<FontSizeOutlined />}
                    title="Heading 1"
                    active={editor.isActive('heading', { level: 1 })}
                  />
                  <ToolbarBtn
                    onClick={() => toggleHeading(2)}
                    icon={<FontSizeOutlined />}
                    title="Heading 2"
                    active={editor.isActive('heading', { level: 2 })}
                  />
                  <ToolbarBtn
                    onClick={() => toggleHeading(3)}
                    icon={<FontSizeOutlined />}
                    title="Heading 3"
                    active={editor.isActive('heading', { level: 3 })}
                  />
                  <ToolbarBtn
                    onClick={() => {
                      editor.chain().focus().toggleBulletList().run();
                    }}
                    icon={<UnorderedListOutlined />}
                    title="Bullet List"
                    active={editor.isActive('bulletList')}
                  />
                  <ToolbarBtn
                    onClick={() => {
                      editor.chain().focus().toggleOrderedList().run();
                    }}
                    icon={<OrderedListOutlined />}
                    title="Ordered List"
                    active={editor.isActive('orderedList')}
                  />
                  <ToolbarBtn
                    onClick={() => {
                      editor.chain().focus().toggleBlockquote().run();
                    }}
                    icon={<InsertRowRightOutlined />}
                    title="Blockquote"
                    active={editor.isActive('blockquote')}
                  />
                  <ToolbarBtn
                    onClick={() => {
                      editor.chain().focus().toggleCodeBlock().run();
                    }}
                    icon={<CodeOutlined />}
                    title="Code Block"
                    active={editor.isActive('codeBlock')}
                  />
                  <ToolbarBtn
                    onClick={() => {
                      editor
                        .chain()
                        .focus()
                        .insertTable({ rows: 3, cols: 3 })
                        .run();
                    }}
                    icon={<TableOutlined />}
                    title="Insert Table"
                    active={editor.isActive('table')}
                  />
                </div>
                <div
                  style={{
                    flex: 1,
                    overflowY: 'auto',
                    overscrollBehavior: 'contain',
                    minHeight: 0,
                  }}
                >
                  <EditorContent editor={editor} />
                </div>
              </div>
              {tocOpen && (
                <TocPanel
                  items={tocItems}
                  onJump={jumpToHeading}
                  visible
                  onClose={() => setTocOpen(false)}
                />
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
