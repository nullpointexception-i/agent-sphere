import dayjs from 'dayjs';
import type { DocumentSummary } from '@/services/agentSphere/api';

export type DocumentKind = 'markdown' | 'text' | 'pdf' | 'word' | 'other';

export function documentKind(document: DocumentSummary): DocumentKind {
  const type = (document.contentType || '').toLowerCase();
  const title = (document.title || '').toLowerCase();
  if (type.includes('pdf') || title.endsWith('.pdf')) return 'pdf';
  if (
    type.includes('word') ||
    type.includes('officedocument') ||
    /\.(docx?|rtf)$/.test(title)
  ) {
    return 'word';
  }
  if (
    type.includes('markdown') ||
    type === 'text/md' ||
    /\.(md|markdown)$/.test(title)
  ) {
    return 'markdown';
  }
  if (type.includes('text') || /\.txt$/.test(title)) return 'text';
  return 'markdown';
}

export function documentKindLabel(kind: DocumentKind): string {
  switch (kind) {
    case 'markdown':
      return 'MD';
    case 'text':
      return 'TXT';
    case 'pdf':
      return 'PDF';
    case 'word':
      return 'DOC';
    default:
      return 'FILE';
  }
}

export function documentPreviewText(
  preview: string | undefined,
  maxLength = 160,
): string {
  if (!preview) return '';
  const text = preview
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#>*_`~[\]()!-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

export function formatDocumentSize(length?: number): string {
  if (!length || length <= 0) return '';
  if (length < 1000) return `${length}`;
  return `${(length / 1000).toFixed(1)}k`;
}

export function documentUpdatedAt(value?: string): string {
  if (!value) return '';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : value;
}

export function documentDownloadName(document: DocumentSummary): string {
  const title = (document.title || `document-${document.id}`).trim();
  if (/\.[a-z0-9]{1,5}$/i.test(title)) return title;
  const kind = documentKind(document);
  return `${title}${kind === 'text' ? '.txt' : '.md'}`;
}

export function downloadDocumentText(fileName: string, content: string): void {
  const blob = new Blob([content ?? ''], {
    type: 'text/plain;charset=utf-8',
  });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}
