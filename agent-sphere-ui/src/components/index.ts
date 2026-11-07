/**
 * This file acts as the component directory.
 * Its purpose is to centralize exported components and keep them categorized.
 */
/**
 * Layout components
 */
import { LangDropdown } from './RightContent';
import { AvatarDropdown } from './RightContent/AvatarDropdown';

/**
 * Business components
 */
export { default as ErrorBoundary } from './ErrorBoundary';
export { default as OfflineBanner } from './OfflineBanner';

export { AvatarDropdown, LangDropdown };
