import {
  BoldOutlined,
  ItalicOutlined,
  OrderedListOutlined,
  StrikethroughOutlined,
  UnorderedListOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import Image from '@tiptap/extension-image';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { App, Button, Space, Tooltip, Upload } from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { tw } from '../../i18n';
import { useAppStyles } from '../style';

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

export function RichTextEditor({
  value,
  onChange,
  minHeight = 120,
}: {
  value?: string;
  onChange?: (value: string) => void;
  minHeight?: number;
}) {
  const { styles } = useAppStyles();
  const { message } = App.useApp();
  const [uploadingImage, setUploadingImage] = useState(false);
  const editor = useEditor({
    extensions: [
      StarterKit.configure({ heading: { levels: [1, 2, 3] } }),
      Image.configure({ allowBase64: true }),
    ],
    content: value || '',
    onUpdate: ({ editor: current }: { editor: { getHTML: () => string } }) =>
      onChange?.(current.getHTML()),
    editorProps: {
      attributes: {
        class: 'project-rich-content',
        style: `min-height: ${minHeight}px`,
      },
    },
  });

  useEffect(() => {
    if (!editor) return;
    const next = value || '';
    if (next !== editor.getHTML()) {
      editor.commands.setContent(next);
    }
  }, [editor, value]);

  const uploadImage = async (file: File) => {
    if (!file.type.startsWith('image/')) {
      message.error(
        tw('projects.richText.imageTypeInvalid', 'Only images are supported'),
      );
      return false;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      message.error(
        tw('projects.richText.imageTooLarge', 'Image must not exceed 5MB'),
      );
      return false;
    }
    setUploadingImage(true);
    try {
      const uploaded = await agentApi.files.upload(file);
      const blob = await agentApi.files.get(uploaded.fileKey);
      const dataUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(blob);
      });
      editor?.chain().focus().setImage({ src: dataUrl, alt: file.name }).run();
    } catch {
      message.error(
        tw('projects.richText.imageUploadFailed', 'Image upload failed'),
      );
    } finally {
      setUploadingImage(false);
    }
    return false;
  };

  if (!editor) return null;

  return (
    <div className={styles.richEditor}>
      <Space size={2} className={styles.richEditorToolbar}>
        <Tooltip title={tw('projects.richText.bold', 'Bold')}>
          <Button
            type={editor.isActive('bold') ? 'primary' : 'text'}
            size="small"
            icon={<BoldOutlined />}
            onClick={() => editor.chain().focus().toggleBold().run()}
          />
        </Tooltip>
        <Tooltip title={tw('projects.richText.italic', 'Italic')}>
          <Button
            type={editor.isActive('italic') ? 'primary' : 'text'}
            size="small"
            icon={<ItalicOutlined />}
            onClick={() => editor.chain().focus().toggleItalic().run()}
          />
        </Tooltip>
        <Tooltip title={tw('projects.richText.strike', 'Strikethrough')}>
          <Button
            type={editor.isActive('strike') ? 'primary' : 'text'}
            size="small"
            icon={<StrikethroughOutlined />}
            onClick={() => editor.chain().focus().toggleStrike().run()}
          />
        </Tooltip>
        <Tooltip title={tw('projects.richText.bulletList', 'Bullet list')}>
          <Button
            type={editor.isActive('bulletList') ? 'primary' : 'text'}
            size="small"
            icon={<UnorderedListOutlined />}
            onClick={() => editor.chain().focus().toggleBulletList().run()}
          />
        </Tooltip>
        <Tooltip title={tw('projects.richText.orderedList', 'Numbered list')}>
          <Button
            type={editor.isActive('orderedList') ? 'primary' : 'text'}
            size="small"
            icon={<OrderedListOutlined />}
            onClick={() => editor.chain().focus().toggleOrderedList().run()}
          />
        </Tooltip>
        <Upload
          accept="image/*"
          showUploadList={false}
          beforeUpload={(file) => {
            void uploadImage(file);
            return false;
          }}
        >
          <Tooltip title={tw('projects.richText.image', 'Insert image')}>
            <Button
              type="text"
              size="small"
              loading={uploadingImage}
              icon={<UploadOutlined />}
            />
          </Tooltip>
        </Upload>
      </Space>
      <EditorContent editor={editor} />
    </div>
  );
}
