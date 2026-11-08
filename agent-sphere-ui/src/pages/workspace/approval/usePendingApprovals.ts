import { useCallback, useEffect, useState } from 'react';
import { TIMING } from '@/constants/timing';
import type { PendingApproval } from '@/services/agentSphere/superAgent';
import { superAgentApi } from '@/services/agentSphere/superAgent';

/** Polls the owner's pending L2 approvals; the DB inbox works across replicas. */
export function usePendingApprovals() {
  const [items, setItems] = useState<PendingApproval[]>([]);
  const [loading, setLoading] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const rows = await superAgentApi.pendingApprovals();
      setItems(Array.isArray(rows) ? rows : []);
    } catch {
      // Keep the last known list on transient failures.
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void reload();
    }, TIMING.APPROVAL_INBOX_POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') void reload();
    };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onVisible);
    };
  }, [reload]);

  return { items, loading, reload, count: items.length };
}
