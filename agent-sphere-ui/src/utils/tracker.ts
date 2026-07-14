import { request } from '@umijs/max';
import { getToken } from '@/utils/auth';

interface FrontendEvent {
  eventType: string;
  page: string;
  durationMs?: number;
  elementPath?: string;
  elementTag?: string;
  elementText?: string;
  selectedText?: string;
  positionX?: number;
  positionY?: number;
}

class UserTracker {
  private pageEnter = Date.now();
  private currentPath = location.pathname;
  private buffer: FrontendEvent[] = [];
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private initialized = false;
  private lastClickKey = '';
  private lastClickTime = 0;
  private lastPageChangeTime = 0;

  init() {
    if (this.initialized) return;
    this.initialized = true;

    this.push({ eventType: 'PAGE_VIEW', page: this.currentPath });

    this.observeNavigation();
    this.observeClicks();
    this.observeSelection();
    this.observeUnload();
  }

  private observeNavigation() {
    const origPushState = history.pushState;
    const origReplaceState = history.replaceState;
    const self = this;

    history.pushState = function (...args) {
      self.onPathChange();
      return origPushState.apply(this, args);
    };
    history.replaceState = function (...args) {
      self.onPathChange();
      return origReplaceState.apply(this, args);
    };
    window.addEventListener('popstate', () => self.onPathChange());
  }

  private onPathChange() {
    const now = Date.now();
    if (now - this.lastPageChangeTime < 2000) return;
    this.lastPageChangeTime = now;
    const dwell = Date.now() - this.pageEnter;
    this.push({
      eventType: 'PAGE_EXIT',
      page: this.currentPath,
      durationMs: dwell,
    });
    this.currentPath = location.pathname;
    this.pageEnter = Date.now();
    this.push({ eventType: 'PAGE_VIEW', page: this.currentPath });
  }

  private observeClicks() {
    document.addEventListener(
      'click',
      (e) => {
        const target = e.target as HTMLElement;

        const elPath = target.closest('[data-track]')
          ? (target.closest('[data-track]') as HTMLElement).getAttribute('data-track') || ''
          : this.buildCssPath(target);

        const clickKey = `${e.clientX},${e.clientY}|${elPath}`;
        const now = Date.now();
        if (clickKey === this.lastClickKey && now - this.lastClickTime < 500) return;
        this.lastClickKey = clickKey;
        this.lastClickTime = now;

        const tracked = target.closest('[data-track]') as HTMLElement | null;
        if (tracked) {
          this.push({
            eventType: 'CLICK',
            page: location.pathname,
            elementPath: tracked.getAttribute('data-track') || '',
            elementTag: tracked.tagName,
            elementText: (tracked.textContent || '').trim().slice(0, 20),
            positionX: e.clientX,
            positionY: e.clientY,
          });
          return;
        }

        this.push({
          eventType: 'CLICK',
          page: location.pathname,
          elementPath: this.buildCssPath(target),
          elementTag: target.tagName,
          elementText: (target.textContent || '').trim().slice(0, 20),
          positionX: e.clientX,
          positionY: e.clientY,
        });
      },
      { capture: true },
    );
  }

  private observeSelection() {
    document.addEventListener('mouseup', () => {
      const sel = window.getSelection();
      if (!sel || sel.isCollapsed) return;
      const text = sel.toString().trim();
      if (text.length < 3) return;
      this.push({
        eventType: 'SELECT',
        page: location.pathname,
        selectedText: text.slice(0, 100),
      });
    });
  }

  private observeUnload() {
    window.addEventListener('beforeunload', () => {
      const dwell = Date.now() - this.pageEnter;
      const body = JSON.stringify([
        {
          eventType: 'DWELL',
          page: this.currentPath,
          durationMs: dwell,
        },
      ]);
      const token = getToken();
      fetch('/api/v1/track/frontend', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body,
        keepalive: true,
      });
    });
  }

  private push(event: FrontendEvent) {
    this.buffer.push(event);
    if (this.buffer.length >= 20) this.flush();
    else if (!this.flushTimer) {
      this.flushTimer = setTimeout(() => this.flush(), 30000);
    }
  }

  private async flush() {
    if (!this.buffer.length) return;
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
    const batch = this.buffer.splice(0);
    try {
      await request('/api/v1/track/frontend', {
        method: 'POST',
        data: batch,
      });
    } catch {}
  }

  private buildCssPath(el: HTMLElement): string {
    const parts: string[] = [];
    let current: HTMLElement | null = el;
    while (current && current !== document.body) {
      let selector = current.tagName.toLowerCase();
      if (current.id) {
        selector = `#${current.id}`;
        parts.unshift(selector);
        break;
      }
      if (current.className && typeof current.className === 'string') {
        const cls = current.className.trim().split(/\s+/).slice(0, 2).join('.');
        if (cls) selector += `.${cls}`;
      }
      parts.unshift(selector);
      current = current.parentElement;
    }
    return parts.join(' > ') || el.tagName;
  }
}

export const tracker = new UserTracker();
