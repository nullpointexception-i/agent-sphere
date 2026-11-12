import type { DocumentFolder } from '@/services/agentSphere/api';
import { tw } from '../i18n';

const SYSTEM_INSTANCE_ROOT = 'INSTANCE_ROOT';
const INSTANCE_FALLBACK_NAME = /^实例\s+(\d+)$/;

/** Localized display name for system-generated folder names. */
export function folderDisplayName(folder: DocumentFolder): string {
  if (folder.systemKey === SYSTEM_INSTANCE_ROOT) {
    return tw('documents.instanceRoot', 'Avatar documents');
  }
  if (folder.instanceId) {
    const match = INSTANCE_FALLBACK_NAME.exec(folder.name);
    if (match) {
      return tw('documents.instanceFallback', 'Instance {id}', {
        id: match[1],
      });
    }
  }
  return folder.name;
}
