import { contextBridge, ipcRenderer } from 'electron';
import type {
  ApprovalDecision,
  ApprovalView,
  BootstrapConfig,
  DesktopBridge,
  DesktopSettings,
  RuntimeStatus,
  TaskChunk,
  TaskView,
} from '../shared/types';
import { IPC } from '../main/ipc/channels';

const bootstrap = ipcRenderer.sendSync(IPC.bootstrap) as BootstrapConfig;
contextBridge.exposeInMainWorld('__AS_RUNTIME__', bootstrap);

function subscribe<T>(channel: string, callback: (payload: T) => void): () => void {
  const listener = (_event: Electron.IpcRendererEvent, payload: T): void => callback(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
}

const bridge: DesktopBridge = {
  info: () => ipcRenderer.invoke(IPC.info),
  settings: {
    get: () => ipcRenderer.invoke(IPC.settingsGet),
    update: (patch: Partial<DesktopSettings>) =>
      ipcRenderer.invoke(IPC.settingsUpdate, patch),
    test: (serverUrl: string) => ipcRenderer.invoke(IPC.settingsTest, serverUrl),
  },
  runtime: {
    status: () => ipcRenderer.invoke(IPC.runtimeStatus),
    configure: (input) => ipcRenderer.invoke(IPC.runtimeConfigure, input),
    pause: () => ipcRenderer.invoke(IPC.runtimePause),
    resume: () => ipcRenderer.invoke(IPC.runtimeResume),
  },
  drivers: {
    detect: () => ipcRenderer.invoke(IPC.driversDetect),
    models: (kind) => ipcRenderer.invoke(IPC.driversModels, kind),
  },
  profiles: {
    list: () => ipcRenderer.invoke(IPC.profilesList),
    save: (profile) => ipcRenderer.invoke(IPC.profilesSave, profile),
    remove: (agentId: number) => ipcRenderer.invoke(IPC.profilesRemove, agentId),
    pickWorkspace: () => ipcRenderer.invoke(IPC.profilesPickWorkspace),
  },
  tasks: {
    list: () => ipcRenderer.invoke(IPC.tasksList),
    cancel: (taskId: number) => ipcRenderer.invoke(IPC.taskCancel, taskId),
  },
  approvals: {
    list: () => ipcRenderer.invoke(IPC.approvalsList),
    respond: (id: string, decision: ApprovalDecision) =>
      ipcRenderer.invoke(IPC.approvalRespond, id, decision),
    onRequest: (callback: (request: ApprovalView) => void) =>
      subscribe(IPC.eventApproval, callback),
  },
  logs: {
    open: () => ipcRenderer.invoke(IPC.logsOpen),
    export: () => ipcRenderer.invoke(IPC.logsExport),
  },
  events: {
    onRuntime: (callback: (status: RuntimeStatus) => void) =>
      subscribe(IPC.eventRuntime, callback),
    onTask: (callback: (task: TaskView) => void) =>
      subscribe(IPC.eventTask, callback),
    onChunk: (callback: (chunk: TaskChunk) => void) =>
      subscribe(IPC.eventChunk, callback),
  },
};

contextBridge.exposeInMainWorld('agentSphereDesktop', bridge);
