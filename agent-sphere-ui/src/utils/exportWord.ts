import {
  AlignmentType,
  BorderStyle,
  Document,
  HeadingLevel,
  Packer,
  Paragraph,
  Table,
  TableCell,
  TableRow,
  TextRun,
} from 'docx';

function parseInline(
  text: string,
): { bold: boolean; italic: boolean; text: string }[] {
  const parts: { bold: boolean; italic: boolean; text: string }[] = [];
  let i = 0;
  let buf = '';
  while (i < text.length) {
    if (text.startsWith('***', i) || text.startsWith('___', i)) {
      if (buf) parts.push({ bold: false, italic: false, text: buf });
      const end = text.indexOf(text[i] + text[i] + text[i], i + 3);
      if (end === -1) {
        buf = text.slice(i);
        break;
      }
      parts.push({ bold: true, italic: true, text: text.slice(i + 3, end) });
      i = end + 3;
      buf = '';
      continue;
    }
    if (text[i] === '*' && text[i + 1] === '*') {
      if (buf) parts.push({ bold: false, italic: false, text: buf });
      const end = text.indexOf('**', i + 2);
      if (end === -1) {
        buf = text.slice(i);
        break;
      }
      parts.push({ bold: true, italic: false, text: text.slice(i + 2, end) });
      i = end + 2;
      buf = '';
      continue;
    }
    if (text[i] === '*' || text[i] === '_') {
      if (buf) parts.push({ bold: false, italic: false, text: buf });
      const end = text.indexOf(text[i], i + 1);
      if (end === -1) {
        buf = text.slice(i);
        break;
      }
      parts.push({ bold: false, italic: true, text: text.slice(i + 1, end) });
      i = end + 1;
      buf = '';
      continue;
    }
    if (text[i] === '`') {
      if (buf) parts.push({ bold: false, italic: false, text: buf });
      const end = text.indexOf('`', i + 1);
      if (end === -1) {
        buf = text.slice(i);
        break;
      }
      parts.push({ bold: false, italic: false, text: text.slice(i + 1, end) });
      i = end + 1;
      buf = '';
      continue;
    }
    buf += text[i];
    i++;
  }
  if (buf) parts.push({ bold: false, italic: false, text: buf });
  return parts;
}

function inlineToTextRuns(
  parts: { bold: boolean; italic: boolean; text: string }[],
): TextRun[] {
  return parts.map(
    (p) =>
      new TextRun({
        text: p.text,
        bold: p.bold,
        italics: p.italic,
        font: 'Microsoft YaHei',
      }),
  );
}

function headingLevel(level: number) {
  return (
    HeadingLevel[`HEADING_${level}` as keyof typeof HeadingLevel] ||
    HeadingLevel.HEADING_1
  );
}

function parseTableRow(line: string): string[] {
  return line
    .split('|')
    .slice(1, -1)
    .map((c) => c.trim());
}

export async function exportDocxToFile(
  title: string,
  markdown: string,
  filename: string,
): Promise<void> {
  const lines = markdown.split('\n');
  const children: (Paragraph | Table)[] = [];

  if (title) {
    children.push(
      new Paragraph({
        heading: HeadingLevel.TITLE,
        alignment: AlignmentType.CENTER,
        children: [
          new TextRun({
            text: title,
            bold: true,
            font: 'Microsoft YaHei',
            size: 36,
          }),
        ],
      }),
      new Paragraph({ spacing: { after: 200 }, children: [] }),
    );
  }

  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    // Code block
    if (line.trimStart().startsWith('```')) {
      i++;
      const codeLines: string[] = [];
      while (i < lines.length && !lines[i].trimStart().startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      i++; // skip ```
      for (const cl of codeLines) {
        children.push(
          new Paragraph({
            spacing: { after: 0 },
            indent: { left: 400 },
            children: [
              new TextRun({
                text: cl,
                font: 'Consolas',
                size: 18,
                color: '333333',
              }),
            ],
          }),
        );
      }
      children.push(new Paragraph({ spacing: { after: 120 }, children: [] }));
      continue;
    }

    // Table
    if (line.includes('|') && line.trim().startsWith('|')) {
      const rows: string[][] = [];
      while (
        i < lines.length &&
        lines[i].includes('|') &&
        lines[i].trim().startsWith('|')
      ) {
        if (lines[i].trim().match(/^\|[\s:-]+\|/)) {
          i++;
          continue; // skip separator row
        }
        rows.push(parseTableRow(lines[i]));
        i++;
      }
      if (rows.length > 0) {
        const tableRows = rows.map(
          (row) =>
            new TableRow({
              children: row.map(
                (cell) =>
                  new TableCell({
                    children: [
                      new Paragraph({
                        children: [
                          new TextRun({
                            text: cell,
                            font: 'Microsoft YaHei',
                            size: 20,
                          }),
                        ],
                      }),
                    ],
                  }),
              ),
            }),
        );
        children.push(new Table({ rows: tableRows }));
        children.push(new Paragraph({ spacing: { after: 120 }, children: [] }));
      }
      continue;
    }

    // Blank line
    if (line.trim() === '') {
      children.push(new Paragraph({ spacing: { after: 0 }, children: [] }));
      i++;
      continue;
    }

    // Heading
    const hMatch = line.match(/^(#{1,6})\s+(.+)$/);
    if (hMatch) {
      const level = hMatch[1].length;
      children.push(
        new Paragraph({
          heading: headingLevel(level),
          spacing: { before: 240, after: 120 },
          children: [new TextRun({ text: hMatch[2], font: 'Microsoft YaHei' })],
        }),
      );
      i++;
      continue;
    }

    // Horizontal rule
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(line.trim())) {
      children.push(
        new Paragraph({
          spacing: { before: 200, after: 200 },
          border: {
            bottom: { style: BorderStyle.SINGLE, size: 6, color: '999999' },
          },
          children: [],
        }),
      );
      i++;
      continue;
    }

    // Blockquote
    if (line.trimStart().startsWith('> ')) {
      const text = line.trimStart().slice(2).trim();
      children.push(
        new Paragraph({
          indent: { left: 400 },
          spacing: { after: 80 },
          children: inlineToTextRuns(parseInline(text)),
        }),
      );
      i++;
      continue;
    }

    // Unordered list
    const ulMatch = line.match(/^(\s*)([-*+])\s+(.+)$/);
    if (ulMatch) {
      const indent = ulMatch[1].length * 200;
      children.push(
        new Paragraph({
          indent: { left: 400 + indent },
          bullet: { level: Math.floor(indent / 400) },
          spacing: { after: 40 },
          children: inlineToTextRuns(parseInline(ulMatch[3])),
        }),
      );
      i++;
      continue;
    }

    // Ordered list
    const olMatch = line.match(/^(\s*)\d+\.\s+(.+)$/);
    if (olMatch) {
      const indent = olMatch[1].length * 200;
      children.push(
        new Paragraph({
          indent: { left: 400 + indent },
          numbering: {
            reference: 'default-numbering',
            level: Math.floor(indent / 400),
          },
          spacing: { after: 40 },
          children: inlineToTextRuns(parseInline(olMatch[2])),
        }),
      );
      i++;
      continue;
    }

    // Regular paragraph
    children.push(
      new Paragraph({
        spacing: { after: 80 },
        children: inlineToTextRuns(parseInline(line)),
      }),
    );
    i++;
  }

  const doc = new Document({
    title,
    sections: [{ children }],
  });

  const blob = await Packer.toBlob(doc);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${filename.replace(/[<>:"/\\|?*]/g, '_')}.docx`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
