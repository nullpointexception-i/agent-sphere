// biome-ignore-all lint/security/noDangerouslySetInnerHtml: sanitized Tiptap output
import { Modal } from 'antd';
import { type MouseEvent as ReactMouseEvent, useState } from 'react';
import { useAppStyles } from '../style';
import { sanitizeRichText } from './richText';

export function RichTextContent({
  value,
  expanded = false,
}: {
  value?: string | null;
  expanded?: boolean;
}) {
  const { styles } = useAppStyles();
  const [previewSrc, setPreviewSrc] = useState<string | null>(null);
  if (!value) return null;
  const openPreview = (event: ReactMouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (target.tagName !== 'IMG') return;
    const src = target.getAttribute('src');
    if (!src) return;
    event.preventDefault();
    event.stopPropagation();
    setPreviewSrc(src);
  };
  return (
    <>
      <div
        className={`${styles.richContent} ${
          expanded ? styles.richContentExpanded : ''
        }`}
        style={
          expanded
            ? { maxHeight: 'none', overflow: 'visible', flex: 'none' }
            : undefined
        }
        onClick={openPreview}
        dangerouslySetInnerHTML={{ __html: sanitizeRichText(value) }}
      />
      <Modal
        open={Boolean(previewSrc)}
        footer={null}
        onCancel={() => setPreviewSrc(null)}
        centered
        width={900}
        destroyOnHidden
      >
        {previewSrc ? (
          <img
            src={previewSrc}
            alt=""
            style={{
              display: 'block',
              maxWidth: '86vw',
              maxHeight: '80vh',
              margin: '0 auto',
            }}
          />
        ) : null}
      </Modal>
    </>
  );
}
