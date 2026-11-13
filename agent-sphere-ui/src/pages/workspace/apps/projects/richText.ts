const ALLOWED_TAGS = new Set([
  'p',
  'br',
  'strong',
  'b',
  'em',
  'i',
  's',
  'del',
  'u',
  'ul',
  'ol',
  'li',
  'h1',
  'h2',
  'h3',
  'blockquote',
  'code',
  'pre',
  'hr',
  'img',
]);

const ALLOWED_ATTRIBUTES: Record<string, Set<string>> = {
  img: new Set(['src', 'alt', 'title']),
};

function isSafeImageSource(value: string) {
  return /^data:image\//i.test(value) || value.startsWith('/api/v1/files/');
}

function cleanChildren(parent: Element) {
  for (const child of Array.from(parent.children)) {
    const tag = child.tagName.toLowerCase();
    if (!ALLOWED_TAGS.has(tag)) {
      const nodes = Array.from(child.childNodes);
      child.replaceWith(...nodes);
      for (const node of nodes) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          cleanChildren(node as Element);
        }
      }
      continue;
    }
    const allowed = ALLOWED_ATTRIBUTES[tag] || new Set<string>();
    for (const attribute of Array.from(child.attributes)) {
      if (!allowed.has(attribute.name)) {
        child.removeAttribute(attribute.name);
        continue;
      }
      if (
        tag === 'img' &&
        attribute.name === 'src' &&
        !isSafeImageSource(attribute.value)
      ) {
        child.removeAttribute(attribute.name);
      }
    }
    cleanChildren(child);
  }
}

/** Keeps Tiptap-generated markup and drops everything else before rendering. */
export function sanitizeRichText(value?: string | null) {
  if (!value) return '';
  if (typeof DOMParser === 'undefined') {
    return value.replace(/<[^>]*>/g, '');
  }
  const doc = new DOMParser().parseFromString(value, 'text/html');
  cleanChildren(doc.body);
  return doc.body.innerHTML;
}
