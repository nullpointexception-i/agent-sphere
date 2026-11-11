import type { DriverKind, DriverModel, DriverStatus } from '../../../shared/types';
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

  async listModels(kind: DriverKind): Promise<DriverModel[]> {
    const driver = this.drivers[kind];
    if (!driver.listModels) return [];
    try {
      return await driver.listModels();
    } catch {
      return [];
    }
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
