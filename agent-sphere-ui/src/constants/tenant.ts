/** Tenant ids with special platform semantics. They are read-only in the UI. */
export const TENANT = {
  PLATFORM_ID: 1,
  DEFAULT_ID: 2,
} as const;

export function isReservedTenant(tenantId: number | null | undefined): boolean {
  return tenantId === TENANT.PLATFORM_ID || tenantId === TENANT.DEFAULT_ID;
}
