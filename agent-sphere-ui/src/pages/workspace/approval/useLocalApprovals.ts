import { useCallback, useEffect, useState } from 'react';
import {
  type DesktopBridgeApproval,
  getDesktopBridge,
} from '@/utils/desktopBridge';

/** Pending approvals raised by the desktop runtime (permission requests). */
export function useLocalApprovals() {
  const bridge = getDesktopBridge();
  const [items, setItems] = useState<DesktopBridgeApproval[]>([]);
  const [loading, setLoading] = useState(false);

  const reload = useCallback(async () => {
    if (!bridge) {
      setItems([]);
      return;
    }
    setLoading(true);
    try {
      const rows = await bridge.approvals.list();
      setItems(rows ?? []);
    } catch {
      // Keep the last known list on transient failures.
    } finally {
      setLoading(false);
    }
  }, [bridge]);

  useEffect(() => {
    if (!bridge) return;
    void reload();
    const off = bridge.approvals.onRequest(() => {
      void reload();
    });
    return off;
  }, [bridge, reload]);

  return { items, loading, reload, count: items.length };
}
