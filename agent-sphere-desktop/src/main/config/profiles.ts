import { readFile, rename, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { paths } from './paths';
import type { AgentProfile } from '../../shared/types';

export class ProfileStore {
  private profiles: AgentProfile[] = [];

  async load(): Promise<void> {
    try {
      const raw = JSON.parse(await readFile(paths.profilesFile(), 'utf8')) as unknown;
      if (Array.isArray(raw)) {
        this.profiles = raw.filter(isProfile);
      }
    } catch {
      this.profiles = [];
    }
  }

  list(): AgentProfile[] {
    return this.profiles;
  }

  find(agentId: number): AgentProfile | undefined {
    return this.profiles.find((profile) => profile.agentId === agentId);
  }

  async save(profile: AgentProfile): Promise<AgentProfile[]> {
    const index = this.profiles.findIndex((item) => item.agentId === profile.agentId);
    if (index >= 0) this.profiles[index] = profile;
    else this.profiles.push(profile);
    await this.persist();
    return this.profiles;
  }

  async remove(agentId: number): Promise<AgentProfile[]> {
    this.profiles = this.profiles.filter((item) => item.agentId !== agentId);
    await this.persist();
    return this.profiles;
  }

  private async persist(): Promise<void> {
    const file = paths.profilesFile();
    await mkdir(path.dirname(file), { recursive: true });
    const tmp = `${file}.${process.pid}.tmp`;
    await writeFile(tmp, JSON.stringify(this.profiles, null, 2));
    await rename(tmp, file);
  }
}

function isProfile(value: unknown): value is AgentProfile {
  if (!value || typeof value !== 'object') return false;
  const row = value as Record<string, unknown>;
  return (
    typeof row.agentId === 'number' &&
    typeof row.agentName === 'string' &&
    (row.driver === 'OPENCODE' || row.driver === 'CODEX') &&
    typeof row.workspace === 'string'
  );
}
