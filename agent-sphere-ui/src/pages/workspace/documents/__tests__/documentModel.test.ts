import {
  documentDownloadName,
  documentKind,
  documentKindLabel,
  documentPreviewText,
  formatDocumentSize,
} from '../documentModel';

describe('workspace document model', () => {
  it('infers the file kind from content type and title', () => {
    expect(documentKind({ id: 1, title: 'notes.md' })).toBe('markdown');
    expect(
      documentKind({ id: 2, title: 'report', contentType: 'application/pdf' }),
    ).toBe('pdf');
    expect(documentKind({ id: 3, title: 'readme.txt' })).toBe('text');
    expect(documentKind({ id: 4, title: 'contract.docx' })).toBe('word');
    expect(documentKind({ id: 5, title: 'data.bin' })).toBe('markdown');
  });

  it('labels each kind for the file glyph', () => {
    expect(documentKindLabel('markdown')).toBe('MD');
    expect(documentKindLabel('text')).toBe('TXT');
    expect(documentKindLabel('pdf')).toBe('PDF');
    expect(documentKindLabel('word')).toBe('DOC');
  });

  it('formats character counts compactly', () => {
    expect(formatDocumentSize(0)).toBe('');
    expect(formatDocumentSize(12)).toBe('12');
    expect(formatDocumentSize(1234)).toBe('1.2k');
  });

  it('strips markdown noise from the preview', () => {
    expect(documentPreviewText('## Title\n**bold** and `code`')).toBe(
      'Title bold and code',
    );
    expect(documentPreviewText(undefined)).toBe('');
  });

  it('keeps an existing extension and adds one for plain titles', () => {
    expect(documentDownloadName({ id: 1, title: 'notes.md' })).toBe('notes.md');
    expect(documentDownloadName({ id: 2, title: 'notes' })).toBe('notes.md');
    expect(
      documentDownloadName({
        id: 3,
        title: 'readme',
        contentType: 'text/plain',
      }),
    ).toBe('readme.txt');
    expect(documentDownloadName({ id: 4, title: 'report.pdf' })).toBe(
      'report.pdf',
    );
  });
});
