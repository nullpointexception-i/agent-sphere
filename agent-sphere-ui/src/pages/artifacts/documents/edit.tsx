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
import { useStyles } from './style';
import { type TocItemBase, TocPanel } from './TocPanel';

interface TocItem extends TocItemBase {
  pos: number;
}

export default function DocumentEdit() {
  const { styles } = useStyles();
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
  const [charCount, setCharCount] = useState(0);
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
    onUpdate: ({ editor: ed }) => {
      if (!ed) return;
      const items: TocItem[] = [];
      ed.state.doc.descendants((node, pos) => {
        if (node.type.name === 'heading') {
          items.push({ level: node.attrs.level, text: node.textContent, pos });
        }
      });
      setTocItems(items);
      const text = ed.state.doc.textContent || '';
      setCharCount(text.length);
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
    setTimeout(() => {
      updateToc();
      const text = editor.state.doc.textContent || '';
      setCharCount(text.length);
    }, 100);
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

  const jumpToHeading = (item: TocItem) => {
    if (!editor) return;
    editor.chain().focus().setTextSelection(item.pos).run();

    let headingEl: HTMLElement | null = null;
    const { node } = editor.view.domAtPos(item.pos + 1);
    let cur: Node | null = node;
    while (cur && cur !== editor.view.dom) {
      if (cur instanceof HTMLElement && /^H[1-3]$/i.test(cur.tagName)) {
        headingEl = cur;
        break;
      }
      cur = cur.parentElement;
    }
    if (headingEl) headingEl.scrollIntoView({ block: 'center' });

    const { top, bottom } = editor.view.coordsAtPos(item.pos + 1);
    const editorRect = editor.view.dom.getBoundingClientRect();

    const overlay = document.createElement('div');
    overlay.style.cssText = `
      position: fixed;
      top: ${top}px;
      left: ${editorRect.left}px;
      width: ${editorRect.width}px;
      height: ${bottom - top}px;
      background: #ffe58f;
      pointer-events: none;
      z-index: 9999;
      opacity: 1;
      transition: opacity 0.6s ease-out;
    `;
    document.body.appendChild(overlay);

    void overlay.offsetHeight;
    overlay.style.opacity = '0';

    setTimeout(() => overlay.remove(), 700);
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
      className={styles.toolbarBtn}
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
      className={styles.container}
      style={{ height: containerHeight }}
    >
      {loading ? (
        <div className={styles.spinWrapper}>
          <Spin size="large" />
        </div>
      ) : (
        <>
          <div className={styles.headerBarEdit}>
            <Button
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/artifacts/documents')}
              className={styles.backBtn}
            >
              {intl.formatMessage({
                id: 'pages.document.back',
                defaultMessage: 'Back',
              })}
            </Button>
            <div className={styles.headerRight}>
              <Button
                icon={<MenuOutlined />}
                type="default"
                size="small"
                onClick={() => setTocOpen(!tocOpen)}
              >
                {intl.formatMessage({ id: 'pages.document.outline', defaultMessage: 'Outline' })}
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
            className={styles.titleInput}
            variant="borderless"
          />

          {editor && (
            <div className={styles.editorWrapper}>
              <div className={styles.editorColumn}>
                <div className={styles.toolbar}>
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
                <div className={styles.editorScroll}>
                  <EditorContent editor={editor} />
                </div>
                <div className={styles.charCountBar}>
                  {charCount.toLocaleString()} chars
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
