import type { DriverKind, DriverStatus } from '../../../shared/types';
import type { SettingsStore } from '../../config/settings';
import type { RuntimeDriver } from './types';
import { OpenCodeDriver } from './opencode';
import { CodexDriver } from './codex/driver';

export class DriverRegistry {
  private readonly drivers: Record<DriverKind, RuntimeDriver>;
  private statuses: DriverStatus[] = [];

  constructor(settings: SettingsStore) {
    this.drivers = {
      OPENCODE: new OpenCodeDriver(settings),
      CODEX: new CodexDriver(settings),
    };
  }

  get(kind: DriverKind): RuntimeDriver {
    return this.drivers[kind];
  }

  getStatuses(): DriverStatus[] {
    return this.statuses;
  }

  async detectAll(): Promise<DriverStatus[]> {
    const results = await Promise.all([
      this.drivers.OPENCODE.detect(),
      this.drivers.CODEX.detect(),
    ]);
    this.statuses = results;
    return results;
  }

  dispose(): void {
    this.drivers.OPENCODE.dispose();
    this.drivers.CODEX.dispose();
  }
}
