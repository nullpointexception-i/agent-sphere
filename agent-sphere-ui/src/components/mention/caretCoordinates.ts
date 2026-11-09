export interface CaretPoint {
  /** Offset from the textarea border box. */
  left: number;
  /** Offset from the textarea border box, already adjusted for scrolling. */
  top: number;
  lineHeight: number;
}

const COPIED_PROPERTIES = [
  'fontFamily',
  'fontSize',
  'fontWeight',
  'fontStyle',
  'fontVariant',
  'letterSpacing',
  'wordSpacing',
  'textIndent',
  'textTransform',
  'lineHeight',
  'paddingTop',
  'paddingRight',
  'paddingBottom',
  'paddingLeft',
  'borderTopWidth',
  'borderRightWidth',
  'borderBottomWidth',
  'borderLeftWidth',
  'borderStyle',
] as const;

/**
 * Measures the caret position inside a textarea.
 *
 * Browsers expose no caret API for textarea, so a hidden mirror element with
 * the same typography renders the text up to the caret and reports the offset
 * of a marker span. Returns null when layout is unavailable (SSR/jsdom).
 */
export function caretCoordinates(
  textarea: HTMLTextAreaElement | null,
  position: number,
): CaretPoint | null {
  if (!textarea || typeof document === 'undefined') {
    return null;
  }
  const computed = window.getComputedStyle(textarea);
  if (!computed) {
    return null;
  }
  const mirror = document.createElement('div');
  const style = mirror.style;
  style.position = 'absolute';
  style.top = '0';
  style.left = '0';
  style.visibility = 'hidden';
  style.pointerEvents = 'none';
  style.whiteSpace = 'pre-wrap';
  style.wordBreak = 'break-word';
  style.overflowWrap = 'break-word';
  style.boxSizing = 'border-box';
  for (const property of COPIED_PROPERTIES) {
    style[property] = computed[property];
  }
  // border-box width of the textarea keeps padding/border offsets aligned.
  style.width = `${textarea.offsetWidth || textarea.clientWidth}px`;
  mirror.textContent = textarea.value.slice(0, Math.max(0, position));
  const marker = document.createElement('span');
  marker.textContent = '\u200b';
  mirror.appendChild(marker);
  document.body.appendChild(mirror);
  const left = marker.offsetLeft;
  const top = marker.offsetTop;
  document.body.removeChild(mirror);
  const fontSize = Number.parseFloat(computed.fontSize) || 14;
  const lineHeight =
    Number.parseFloat(computed.lineHeight) || Math.round(fontSize * 1.5);
  return {
    left: left - textarea.scrollLeft,
    top: top - textarea.scrollTop,
    lineHeight,
  };
}
