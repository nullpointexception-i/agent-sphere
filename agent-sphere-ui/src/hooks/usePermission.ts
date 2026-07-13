import { useModel } from '@umijs/max';

export function useCan(code: string): boolean {
  const { initialState } = useModel('@@initialState');
  return initialState?.permissions?.includes(code) ?? false;
}

export function useHasAny(codes: string[]): boolean {
  const { initialState } = useModel('@@initialState');
  const perms = initialState?.permissions ?? [];
  return codes.some((c) => perms.includes(c));
}
