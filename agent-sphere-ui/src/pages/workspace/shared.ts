import { tw } from './i18n';

const AVATAR_PALETTE = [
  'linear-gradient(135deg, #8b7bff, #5b8cff)',
  'linear-gradient(135deg, #ff9f8a, #ff6b8a)',
  'linear-gradient(135deg, #5ec9c0, #3aa6a0)',
  'linear-gradient(135deg, #ffc46b, #e8a33d)',
  'linear-gradient(135deg, #9d8bff, #c86dd7)',
  'linear-gradient(135deg, #67b8ff, #3f8cff)',
];

export function initials(name?: string) {
  const text = (name || '?').trim();
  return text.slice(0, 1).toUpperCase();
}

export function displayName(user: any) {
  return (
    user?.displayName ||
    user?.englishName ||
    user?.username ||
    user?.name ||
    tw('common.unnamed', '未命名')
  );
}

export function avatarStyle(name?: string) {
  const text = (name || '?').trim();
  let hash = 0;
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 31 + text.charCodeAt(index)) % 997;
  }
  return { backgroundImage: AVATAR_PALETTE[hash % AVATAR_PALETTE.length] };
}
