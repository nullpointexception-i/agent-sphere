import {
  ArrowsAltOutlined,
  CloseOutlined,
  CompressOutlined,
  MinusOutlined,
  PushpinOutlined,
} from '@ant-design/icons';
import { Modal } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

interface PiPWindowProps {
  screenshot: string | null;
  artifact: string | null;
}

const NORMAL_W = 280;
const NORMAL_H = 200;
const EXPANDED_W = 980;
const EXPANDED_H = 660;
const MIN_W = 200;
const MIN_H = 150;

const PIP_STYLES = `
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 100%; height: 100%; overflow: hidden; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
  #pip-root {
    width: 100%; height: 100%; display: flex; flex-direction: column; background: #fff;
  }
  #pip-img-wrap {
    flex: 1; display: flex; align-items: center; justify-content: center;
    overflow: hidden; background: #fafafa;
  }
  #pip-img { max-width: 100%; max-height: 100%; object-fit: contain; }
  #pip-url {
    padding: 2px 6px; background: rgba(0,0,0,0.6); color: #fff;
    font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    flex-shrink: 0;
  }
`;

function updatePipContent(pip: Window, ss: string | null, url: string | null) {
  if (!pip?.document) return;
  const img = pip.document.getElementById('pip-img') as HTMLImageElement | null;
  if (img && ss) img.src = `data:image/jpeg;base64,${ss}`;
  const urlEl = pip.document.getElementById('pip-url');
  if (urlEl) urlEl.textContent = url || '';
}

export default function PiPWindow({ screenshot, artifact }: PiPWindowProps) {
  const [minimized, setMinimized] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [previewVisible, setPreviewVisible] = useState(false);
  const [pos, setPos] = useState(() => ({
    x: typeof window !== 'undefined' ? window.innerWidth - NORMAL_W - 16 : 0,
    y: typeof window !== 'undefined' ? window.innerHeight - NORMAL_H - 16 : 0,
  }));
  const [size, setSize] = useState({ w: NORMAL_W, h: NORMAL_H });
  const [dragging, setDragging] = useState<'move' | 'resize' | null>(null);
  const dragRef = useRef({
    startX: 0,
    startY: 0,
    startL: 0,
    startT: 0,
    startW: 0,
    startH: 0,
  });
  const ratioRef = useRef(NORMAL_W / NORMAL_H);
  const pipWindowRef = useRef<Window | null>(null);
  const [detached, setDetached] = useState(false);

  useEffect(() => {
    if (!screenshot) return;
    const img = new Image();
    img.onload = () => {
      ratioRef.current = img.naturalWidth / img.naturalHeight;
    };
    img.src = `data:image/jpeg;base64,${screenshot}`;
  }, [screenshot]);

  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => {
      if (dragging === 'move') {
        setPos({
          x: dragRef.current.startL + e.clientX - dragRef.current.startX,
          y: dragRef.current.startT + e.clientY - dragRef.current.startY,
        });
      } else if (dragging === 'resize') {
        const nw = Math.max(
          MIN_W,
          dragRef.current.startW + e.clientX - dragRef.current.startX,
        );
        setSize({
          w: nw,
          h: Math.max(MIN_H, Math.round(nw / ratioRef.current)),
        });
      }
    };
    const onUp = () => setDragging(null);
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [dragging]);

  useEffect(() => {
    if (screenshot) {
      if (pipWindowRef.current) {
        updatePipContent(pipWindowRef.current, screenshot, pageUrl);
      }
      if (!detached) setHidden(false);
    }
  }, [screenshot, artifact, detached]);

  const pageUrl = useMemo(() => {
    if (!artifact) return null;
    try {
      const parsed = JSON.parse(artifact);
      return parsed?.data?.url || null;
    } catch {
      return null;
    }
  }, [artifact]);

  const openPip = useCallback(async () => {
    try {
      const dpip = (window as any).documentPictureInPicture;
      if (!dpip) return;
      const pip = await dpip.requestWindow({ width: 480, height: 360 });
      const styleEl = pip.document.createElement('style');
      styleEl.textContent = PIP_STYLES;
      pip.document.head.appendChild(styleEl);
      pip.document.body.innerHTML = `
        <div id="pip-root">
          <div id="pip-img-wrap">
            <img id="pip-img" alt="page screenshot" />
          </div>
          <div id="pip-url"></div>
        </div>
      `;
      updatePipContent(pip, screenshot, pageUrl);
      pip.addEventListener('pagehide', () => {
        pipWindowRef.current = null;
        setDetached(false);
      });
      pipWindowRef.current = pip;
      setDetached(true);
      setHidden(true);
    } catch {}
  }, [screenshot, pageUrl]);

  const curW = minimized ? 48 : size.w;
  const curH = minimized ? 48 : size.h;

  if (!screenshot) return null;

  return (
    <>
      {!hidden && !detached && (
        <div
          style={{
            position: 'fixed',
            left: pos.x,
            top: pos.y,
            width: curW,
            height: curH,
            background: '#fff',
            border: '1px solid #e8e8e8',
            borderRadius: 10,
            boxShadow: '0 4px 20px rgba(0,0,0,0.12)',
            zIndex: 1000,
            display: 'flex',
            flexDirection: 'column',
            cursor: minimized ? 'pointer' : dragging ? 'grabbing' : 'default',
            userSelect: dragging ? 'none' : 'auto',
          }}
          onClick={
            minimized
              ? () => {
                  setMinimized(false);
                  setSize({ w: NORMAL_W, h: NORMAL_H });
                  setPos({
                    x: window.innerWidth - NORMAL_W - 16,
                    y: window.innerHeight - NORMAL_H - 16,
                  });
                }
              : undefined
          }
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '4px 8px',
              background: '#f5f5f5',
              borderBottom: '1px solid #e8e8e8',
              flexShrink: 0,
              cursor: minimized ? 'default' : 'grab',
            }}
            onMouseDown={(e) => {
              if (minimized) return;
              dragRef.current = {
                ...dragRef.current,
                startX: e.clientX,
                startY: e.clientY,
                startL: pos.x,
                startT: pos.y,
              };
              setDragging('move');
            }}
          >
            <span style={{ fontSize: 12, fontWeight: 600, color: '#333' }}>
              🖥️ Page View
            </span>
            <div style={{ display: 'flex', gap: 4 }}>
              {(window as any).documentPictureInPicture && (
                <span
                  style={{
                    fontSize: 12,
                    cursor: 'pointer',
                    color: '#1677ff',
                    padding: '0 2px',
                  }}
                  title="Detach to system window"
                  onClick={(e) => {
                    e.stopPropagation();
                    openPip();
                  }}
                >
                  <PushpinOutlined />
                </span>
              )}
              <span
                style={{
                  fontSize: 12,
                  cursor: 'pointer',
                  color: '#999',
                  padding: '0 2px',
                }}
                title={minimized ? 'Expand' : 'Minimize'}
                onClick={(e) => {
                  e.stopPropagation();
                  setMinimized(true);
                  setExpanded(false);
                  setPos({
                    x: window.innerWidth - 64,
                    y: window.innerHeight - 64,
                  });
                }}
              >
                {minimized ? <ArrowsAltOutlined /> : <MinusOutlined />}
              </span>
              <span
                style={{
                  fontSize: 12,
                  cursor: 'pointer',
                  color: '#999',
                  padding: '0 2px',
                }}
                title={expanded ? 'Normal' : 'Large View'}
                onClick={(e) => {
                  e.stopPropagation();
                  if (expanded) {
                    setSize({ w: NORMAL_W, h: NORMAL_H });
                    setExpanded(false);
                    setPos({
                      x: window.innerWidth - NORMAL_W - 16,
                      y: window.innerHeight - NORMAL_H - 16,
                    });
                  } else {
                    setSize({ w: EXPANDED_W, h: EXPANDED_H });
                    setExpanded(true);
                    setPos({
                      x: window.innerWidth - EXPANDED_W - 16,
                      y: window.innerHeight - EXPANDED_H - 16,
                    });
                  }
                  setMinimized(false);
                }}
              >
                {expanded ? <CompressOutlined /> : <ArrowsAltOutlined />}
              </span>
              <span
                style={{
                  fontSize: 12,
                  cursor: 'pointer',
                  color: '#999',
                  padding: '0 2px',
                }}
                title="Close"
                onClick={(e) => {
                  e.stopPropagation();
                  setHidden(true);
                }}
              >
                <CloseOutlined />
              </span>
            </div>
          </div>

          {!minimized && (
            <div
              style={{
                flex: 1,
                overflow: 'hidden',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: '#fafafa',
                position: 'relative',
              }}
            >
              {screenshot ? (
                <>
                  <img
                    src={`data:image/jpeg;base64,${screenshot}`}
                    style={{
                      objectFit: 'contain',
                      maxWidth: '100%',
                      maxHeight: '100%',
                      cursor: 'zoom-in',
                    }}
                    onClick={() => setPreviewVisible(true)}
                    alt="page screenshot"
                  />
                  <Modal
                    open={previewVisible}
                    footer={null}
                    onCancel={() => setPreviewVisible(false)}
                    width="80vw"
                    centered
                  >
                    <img
                      src={`data:image/jpeg;base64,${screenshot}`}
                      style={{ width: '100%', objectFit: 'contain' }}
                      alt="page screenshot full"
                    />
                  </Modal>
                </>
              ) : (
                <span style={{ color: '#ccc', fontSize: 12 }}>
                  No page data
                </span>
              )}
              {pageUrl && (
                <div
                  style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    right: 0,
                    padding: '2px 6px',
                    background: 'rgba(0,0,0,0.6)',
                    color: '#fff',
                    fontSize: 10,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {pageUrl}
                </div>
              )}
            </div>
          )}

          {!minimized && (
            <div
              style={{
                position: 'absolute',
                bottom: 0,
                right: 0,
                width: 24,
                height: 24,
                cursor: 'nwse-resize',
                zIndex: 2,
              }}
              onMouseDown={(e) => {
                e.stopPropagation();
                e.preventDefault();
                dragRef.current = {
                  ...dragRef.current,
                  startW: size.w,
                  startH: size.h,
                  startX: e.clientX,
                  startY: e.clientY,
                };
                setDragging('resize');
              }}
            >
              <svg
                viewBox="0 0 10 10"
                style={{
                  width: 16,
                  height: 16,
                  position: 'absolute',
                  bottom: 4,
                  right: 4,
                }}
              >
                <path d="M0 10 L10 0" stroke="#bbb" strokeWidth="1.5" />
                <path d="M0 7 L7 0" stroke="#bbb" strokeWidth="1.5" />
                <path d="M0 4 L4 0" stroke="#bbb" strokeWidth="1.5" />
              </svg>
            </div>
          )}
        </div>
      )}
    </>
  );
}
