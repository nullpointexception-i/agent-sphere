import type { DocumentFolder } from '@/services/agentSphere/api';
import {
  buildFolderTree,
  descendantFolderIds,
  extractToc,
  folderMoveOptions,
  folderPath,
} from '../wikiModel';

function folder(
  id: number,
  name: string,
  parentId: number | null = null,
): DocumentFolder {
  return { id, name, parentId };
}

describe('wiki model', () => {
  it('extracts h1-h3 headings outside code fences', () => {
    const toc = extractToc(
      ['# Intro', '## Details', '```', '# not a heading', '```', '### Deep'].join(
        '\n',
      ),
    );
    expect(toc.map((item) => [item.level, item.text])).toEqual([
      [1, 'Intro'],
      [2, 'Details'],
      [3, 'Deep'],
    ]);
  });

  it('builds a nested folder tree sorted by id', () => {
    const tree = buildFolderTree([
      folder(3, 'Child', 1),
      folder(1, 'Root'),
      folder(2, 'Root2'),
    ]);
    expect(tree.map((node) => node.name)).toEqual(['Root', 'Root2']);
    expect(tree[0].children.map((node) => node.name)).toEqual(['Child']);
  });

  it('resolves the breadcrumb path', () => {
    const folders = [folder(1, 'Root'), folder(2, 'Child', 1), folder(3, 'Leaf', 2)];
    expect(folderPath(folders, 3).map((item) => item.name)).toEqual([
      'Root',
      'Child',
      'Leaf',
    ]);
  });

  it('lists descendants including the folder itself', () => {
    const folders = [folder(1, 'Root'), folder(2, 'Child', 1), folder(3, 'Leaf', 2)];
    expect(descendantFolderIds(folders, 1).sort()).toEqual([1, 2, 3]);
  });

  it('excludes the moved subtree from target options', () => {
    const folders = [folder(1, 'Root'), folder(2, 'Child', 1), folder(3, 'Other')];
    expect(folderMoveOptions(folders, 1).map((item) => item.value)).toEqual([3]);
  });
});
