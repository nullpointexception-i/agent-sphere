import type { ImMessage } from '@/services/agentSphere/im';

export function parsedContent(message: ImMessage): Record<string, any> {
  const content = message.content;
  if (!content) return {};
  if (typeof content === 'string') {
    try {
      return JSON.parse(content);
    } catch {
      return { text: content };
    }
  }
  return content;
}

export function messageText(message: ImMessage): string {
  const content = parsedContent(message);
  return content.text ?? content.summary ?? '';
}
