import type { ReactNode } from 'react';
import { useCan } from '@/hooks/usePermission';

interface CanProps {
  code: string;
  children: ReactNode;
  fallback?: ReactNode;
}

export function Can({ code, children, fallback }: CanProps) {
  const ok = useCan(code);
  if (ok) return children;
  return fallback;
}
