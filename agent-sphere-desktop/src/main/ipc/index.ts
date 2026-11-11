import { app, dialog, ipcMain } from 'electron';
import type { BrowserWindow } from 'electron';
import { z } from 'zod';
import type {
  AgentProfile,
  ApprovalDecision,
  BootstrapConfig,
  DesktopInfo,
  DesktopSettings,
  RuntimeConfigureInput,
} from '../../shared/types';
import type { SettingsStore } from '../config/settings';
import type { ProfileStore } from '../config/profiles';
import type { DriverRegistry } from '../runtime/drivers/registry';
import type { RuntimeManager } from '../runtime/manager';
import type { ApprovalBroker } from '../runtime/approval';
import { IPC } from './channels';

const settingsPatchSchema = z.object({
  serverUrl: z.string().min(1).optional(),
  webOrigin: z.string().min(1).optional(),
  runtimeName: z.string().min(1).optional(),
  language: z.enum(['system', 'zh-CN', 'en-US']).optional(),
  startAtLogin: z.boolean().optional(),
  closeToTray: z.boolean().optional(),
  maxConcurrentTasks: z.number().int().min(1).max(8).optional(),
  taskTimeoutSec: z.number().int().min(30).max(7_200).optional(),
  approvalTimeoutSec: z.number().int().min(10).max(3_600).optional(),
  autoApprove: z
    .object({
      readOnly: z.boolean().optional(),
      network: z.boolean().optional(),
      fileWrite: z.boolean().optional(),
      shell: z.boolean().optional(),
    })
    .optional(),
  telemetryEnabled: z.boolean().optional(),
  codexPath: z.string().optional(),
});

const configureSchema = z.object({
  serverUrl: z.string().min(1),
  deviceToken: z.string().min(1),
  runtimeId: z.number().int().positive(),
  runtimeName: z.string().min(1).optional(),
});

const profileSchema = z.object({
  agentId: z.number().int().positive(),
  agentName: z.string().min(1),
  driver: z.enum(['OPENCODE', 'CODEX']),
  workspace: z.string().min(1),
  additionalDirs: z.array(z.string()).optional(),
  model: z.string().optional(),
  approvalPolicy: z.enum(['untrusted', 'on-request', 'never']),
  sandbox: z.enum(['read-only', 'workspace-write', 'danger-full-access']),
  autoRun: z.enum(['always', 'mention-only', 'confirm']),
  enabled: z.boolean(),
});

const approvalSchema = z.object({
  id: z.string().min(1),
  decision: z.enum(['accept', 'acceptForSession', 'decline', 'cancel']),
});

export interface IpcDependencies {
  settings: SettingsStore;
  profiles: ProfileStore;
  drivers: DriverRegistry;
  runtime: RuntimeManager;
  approvals: ApprovalBroker;
  bootstrap: () => BootstrapConfig;
  info: () => DesktopInfo;
  window: () => BrowserWindow | null;
  reloadWindow: () => void;
  openLogs: () => Promise<void>;
  exportLogs: () => Promise<string>;
}

export function registerIpc(deps: IpcDependencies): void {
  ipcMain.on(IPC.bootstrap, (event) => {
    event.returnValue = deps.bootstrap();
  });

  ipcMain.handle(IPC.info, () => deps.info());
  ipcMain.handle(IPC.settingsGet, () => deps.settings.get());

  ipcMain.handle(IPC.settingsUpdate, async (_event, raw: unknown) => {
    const patch = settingsPatchSchema.parse(raw) as Partial<DesktopSettings>;
    const before = deps.settings.get();
    const next = await deps.settings.update(patch);
    applyLoginItem(next);
    if (before.serverUrl !== next.serverUrl || before.webOrigin !== next.webOrigin) {
      await deps.runtime.restart();
      deps.reloadWindow();
    }
    return next;
  });

  ipcMain.handle(IPC.runtimeStatus, () => deps.runtime.getStatus());

  ipcMain.handle(IPC.runtimeConfigure, async (_event, raw: unknown) => {
    const input = configureSchema.parse(raw) as RuntimeConfigureInput;
    const serverUrl = input.serverUrl.replace(/\/+$/, '');
    new URL(serverUrl);
    await deps.settings.update({
      serverUrl,
      webOrigin: serverUrl,
      runtimeId: input.runtimeId,
      ...(input.runtimeName ? { runtimeName: input.runtimeName } : {}),
    });
    await deps.settings.updateSecrets({ deviceToken: input.deviceToken });
    await deps.runtime.restart();
    return deps.runtime.getStatus();
  });

  ipcMain.handle(IPC.runtimePause, () => deps.runtime.pause());
  ipcMain.handle(IPC.runtimeResume, () => deps.runtime.resume());
  ipcMain.handle(IPC.driversDetect, () => deps.runtime.detectDrivers());

  ipcMain.handle(IPC.profilesList, () => deps.profiles.list());
  ipcMain.handle(IPC.profilesSave, async (_event, raw: unknown) => {
    const profile = profileSchema.parse(raw) as AgentProfile;
    return deps.profiles.save(profile);
  });
  ipcMain.handle(IPC.profilesRemove, async (_event, agentId: unknown) => {
    const id = z.number().int().positive().parse(agentId);
    return deps.profiles.remove(id);
  });
  ipcMain.handle(IPC.profilesPickWorkspace, async () => {
    const parent = deps.window();
    try {
      const result = parent
        ? await dialog.showOpenDialog(parent, { properties: ['openDirectory'] })
        : await dialog.showOpenDialog({ properties: ['openDirectory'] });
      return result.canceled ? null : (result.filePaths[0] ?? null);
    } catch {
      return null;
    }
  });

  ipcMain.handle(IPC.tasksList, () => deps.runtime.listTasks());
  ipcMain.handle(IPC.taskCancel, async (_event, taskId: unknown) => {
    const id = z.number().int().positive().parse(taskId);
    await deps.runtime.cancelTask(id);
  });

  ipcMain.handle(IPC.approvalsList, () => deps.approvals.list());
  ipcMain.handle(IPC.approvalRespond, (_event, raw: unknown) => {
    const parsed = approvalSchema.parse(raw) as {
      id: string;
      decision: ApprovalDecision;
    };
    deps.approvals.respond(parsed.id, parsed.decision);
  });

  ipcMain.handle(IPC.logsOpen, () => deps.openLogs());
  ipcMain.handle(IPC.logsExport, () => deps.exportLogs());
}

function applyLoginItem(settings: DesktopSettings): void {
  if (process.platform === 'linux') return;
  app.setLoginItemSettings({ openAtLogin: settings.startAtLogin });
}
