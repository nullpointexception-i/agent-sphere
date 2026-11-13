import type { DocumentFolder } from '@/services/agentSphere/api';

export interface TocItem {
  level: number;
  text: string;
  index: number;
}

export interface FolderNode extends DocumentFolder {
  children: FolderNode[];
}

const HEADING = /^(#{1,3})\s+(.+?)\s*#*\s*$/;

/** Minimal wiki table of contents: ATX h1-h3 outside fenced code blocks. */
export function extractToc(markdown: string | undefined): TocItem[] {
  if (!markdown) return [];
  const items: TocItem[] = [];
  let fenced = false;
  let index = 0;
  for (const line of markdown.split(/\r?\n/)) {
    if (/^\s*```/.test(line)) {
      fenced = !fenced;
      continue;
    }
    if (fenced) continue;
    const match = HEADING.exec(line.trim());
    if (!match) continue;
    const text = match[2]
      .replace(/`([^`]*)`/g, '$1')
      .replace(/[*_~]/g, '')
      .trim();
    if (!text) continue;
    items.push({ level: match[1].length, text, index: index++ });
  }
  return items;
}

/** Build a parent/child tree from the flat folder list returned by the API. */
export function buildFolderTree(
  folders: DocumentFolder[],
  rootParentId: number | null = null,
): FolderNode[] {
  const byParent = new Map<number | null, DocumentFolder[]>();
  for (const folder of folders) {
    const parent = folder.parentId ?? null;
    const list = byParent.get(parent) ?? [];
    list.push(folder);
    byParent.set(parent, list);
  }
  const build = (parentId: number | null): FolderNode[] =>
    (byParent.get(parentId) ?? [])
      .slice()
      .sort(
        (left, right) =>
          (left.sortOrder ?? 0) - (right.sortOrder ?? 0) || left.id - right.id,
      )
      .map((folder) => ({
        ...folder,
        children: build(folder.id),
      }));
  return build(rootParentId);
}

export function findFolder(
  folders: DocumentFolder[],
  folderId: number | null | undefined,
): DocumentFolder | undefined {
  if (folderId == null) return undefined;
  return folders.find((folder) => folder.id === folderId);
}

/** Path from the root to the folder, used for the breadcrumb. */
export function folderPath(
  folders: DocumentFolder[],
  folderId: number | null | undefined,
): DocumentFolder[] {
  if (folderId == null) return [];
  const byId = new Map(folders.map((folder) => [folder.id, folder]));
  const path: DocumentFolder[] = [];
  let current = byId.get(folderId);
  const seen = new Set<number>();
  while (current && !seen.has(current.id)) {
    seen.add(current.id);
    path.unshift(current);
    current = current.parentId == null ? undefined : byId.get(current.parentId);
  }
  return path;
}

/** All folder ids in a subtree, including the folder itself. */
export function descendantFolderIds(
  folders: DocumentFolder[],
  folderId: number,
): number[] {
  const result: number[] = [];
  const walk = (id: number): void => {
    result.push(id);
    for (const child of folders) {
      if (child.parentId === id) walk(child.id);
    }
  };
  walk(folderId);
  return result;
}

/** Options for "move to folder" pickers; excludes the moved subtree. */
export function folderMoveOptions(
  folders: DocumentFolder[],
  excludedId?: number,
): Array<{ value: number; label: string; depth: number }> {
  const excluded = excludedId
    ? new Set(descendantFolderIds(folders, excludedId))
    : new Set<number>();
  const options: Array<{ value: number; label: string; depth: number }> = [];
  const walk = (parentId: number | null, depth: number): void => {
    for (const folder of folders
      .filter((item) => (item.parentId ?? null) === parentId)
      .sort((left, right) => left.id - right.id)) {
      if (excluded.has(folder.id)) continue;
      options.push({ value: folder.id, label: folder.name, depth });
      walk(folder.id, depth + 1);
    }
  };
  walk(null, 0);
  return options;
}
