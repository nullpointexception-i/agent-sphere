import { MentionSuggest } from '@/components/mention/MentionSuggest';
import type { MentionPosition } from '@/components/mention/useMentionSuggest';
import { tw } from '../i18n';

export interface MentionPickerProps {
  open: boolean;
  items: any[];
  activeIndex: number;
  position: MentionPosition | null;
  onHover: (index: number) => void;
  onSelect: (candidate: any) => void;
}

/** Presentational wrapper that owns the localized labels of the @ picker. */
export function MentionPicker({
  open,
  items,
  activeIndex,
  position,
  onHover,
  onSelect,
}: MentionPickerProps) {
  return (
    <MentionSuggest
      open={open}
      items={items}
      activeIndex={activeIndex}
      position={position}
      labels={{
        agent: tw('chat.mentionCandidateAgent', 'Avatar'),
        user: tw('chat.mentionCandidateUser', 'Member'),
        self: tw('chat.mentionCandidateSelf', 'Me'),
      }}
      onHover={onHover}
      onSelect={onSelect}
    />
  );
}
