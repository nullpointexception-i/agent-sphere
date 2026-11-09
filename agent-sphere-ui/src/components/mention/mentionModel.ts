import { userDisplayLabel } from '@/utils/userLabel';

/** Participant kinds that can be mentioned from the composer. */
export type MentionType = 'AGENT' | 'USER';

export interface MentionCandidate {
  /** Stable identity used for React keys and de-duplication. */
  key: string;
  type: MentionType;
  id: number;
  /** Text inserted after `@` and matched back from the message body. */
  name: string;
  avatar?: string;
  role?: string;
  /** AGENT only: the member this avatar belongs to, used to nest it in the tree. */
  ownerId?: number | null;
  /** USER only: this is the current user. */
  isSelf?: boolean;
}

/** One rendered row of the suggestion tree. */
export interface MentionRow {
  key: string;
  candidate: MentionCandidate;
  /** 0 = member/owner (or an avatar whose owner is absent), 1 = nested avatar. */
  depth: number;
  /** Member row that has at least one avatar nested below it. */
  hasChildren: boolean;
}

export interface MentionRange {
  /** Index of the `@` that opened the suggestion list. */
  start: number;
  /** Caret position when the list was opened (exclusive slice end). */
  end: number;
  /** Text typed between `@` and the caret. */
  query: string;
}

export interface InsertedMention {
  text: string;
  caret: number;
}

const AGENT = 'AGENT';
const USER = 'USER';

function textOf(value: unknown): string {
  return value == null ? '' : String(value);
}

/**
 * Builds the mention candidates of one conversation.
 *
 * Agents and human members share the same list; every avatar carries its owner
 * id so the popup can nest it under that member.
 */
export function buildMentionCandidates(
  participants: any[] | null | undefined,
  selfUserId?: number | null,
  ownerFallback?: Record<number, number>,
): MentionCandidate[] {
  const seen = new Set<string>();
  const candidates: MentionCandidate[] = [];
  for (const participant of participants || []) {
    const type = textOf(participant?.participantType).toUpperCase();
    if (type !== AGENT && type !== USER) {
      continue;
    }
    const id = Number(participant?.participantId);
    if (!Number.isFinite(id)) {
      continue;
    }
    const key = `${type}:${id}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    const name =
      type === AGENT
        ? textOf(participant?.displayName) ||
          textOf(participant?.username) ||
          textOf(participant?.name) ||
          `Agent ${id}`
        : userDisplayLabel(participant);
    candidates.push({
      key,
      type,
      id,
      name,
      avatar: participant?.avatar,
      role: participant?.role,
      ownerId:
        type === AGENT
          ? (toId(participant?.ownerUserId) ?? toId(ownerFallback?.[id]))
          : null,
      isSelf: type === USER && selfUserId != null && id === Number(selfUserId),
    });
  }
  return candidates.sort((left, right) => compareCandidates(left, right));
}

function toId(value: unknown): number | null {
  const id = Number(value);
  return Number.isFinite(id) && id > 0 ? id : null;
}

function compareCandidates(left: MentionCandidate, right: MentionCandidate) {
  if (left.type !== right.type) {
    return left.type === AGENT ? -1 : 1;
  }
  return left.name.localeCompare(right.name);
}

/** Filters by the text typed after `@`; an empty query lists everything. */
export function filterMentionCandidates(
  candidates: MentionCandidate[],
  query: string,
): MentionCandidate[] {
  const keyword = (query || '').trim().toLowerCase();
  if (!keyword) {
    return candidates;
  }
  return candidates.filter((candidate) =>
    candidate.name.toLowerCase().includes(keyword),
  );
}

/**
 * Orders candidates as a two-level tree: each member, then the avatars they own.
 *
 * Avatars whose owner is not a member of this conversation stay at the top
 * level so they never disappear from the list. A member that does not match the
 * query is still rendered (without children) when one of their avatars matches,
 * so the tree keeps its owner context while searching.
 */
export function arrangeMentionRows(
  candidates: MentionCandidate[],
  query: string,
): MentionRow[] {
  const matched = new Set(
    filterMentionCandidates(candidates, query).map(
      (candidate) => candidate.key,
    ),
  );
  const members = candidates
    .filter((candidate) => candidate.type === 'USER')
    .sort(
      (left, right) =>
        Number(Boolean(right.isSelf)) - Number(Boolean(left.isSelf)) ||
        left.name.localeCompare(right.name),
    );
  const memberIds = new Set(members.map((member) => member.id));
  const agentsByOwner = new Map<number, MentionCandidate[]>();
  const orphans: MentionCandidate[] = [];
  for (const candidate of candidates) {
    if (candidate.type !== 'AGENT') {
      continue;
    }
    const ownerId = candidate.ownerId;
    if (ownerId != null && memberIds.has(ownerId)) {
      const children = agentsByOwner.get(ownerId) ?? [];
      children.push(candidate);
      agentsByOwner.set(ownerId, children);
    } else {
      orphans.push(candidate);
    }
  }
  const rows: MentionRow[] = [];
  for (const member of members) {
    const children = (agentsByOwner.get(member.id) ?? []).sort((left, right) =>
      left.name.localeCompare(right.name),
    );
    const memberMatched = matched.has(member.key);
    const visible = memberMatched
      ? children
      : children.filter((child) => matched.has(child.key));
    if (!memberMatched && visible.length === 0) {
      continue;
    }
    rows.push({
      key: member.key,
      candidate: member,
      depth: 0,
      hasChildren: children.length > 0,
    });
    for (const child of visible) {
      rows.push({
        key: child.key,
        candidate: child,
        depth: 1,
        hasChildren: false,
      });
    }
  }
  for (const orphan of orphans
    .filter((candidate) => matched.has(candidate.key))
    .sort((left, right) => left.name.localeCompare(right.name))) {
    rows.push({
      key: orphan.key,
      candidate: orphan,
      depth: 0,
      hasChildren: false,
    });
  }
  return rows;
}

/**
 * Detects an active `@query` token at the caret.
 *
 * The `@` must start a word (line start or after whitespace) and the text
 * between it and the caret must not contain whitespace, otherwise the user is
 * just typing normal prose or an e-mail address.
 */
export function detectMentionQuery(
  text: string,
  caret?: number | null,
): MentionRange | null {
  if (typeof text !== 'string' || !text) {
    return null;
  }
  const end = Math.max(
    0,
    Math.min(caret == null ? text.length : caret, text.length),
  );
  const start = text.lastIndexOf('@', end - 1);
  if (start < 0) {
    return null;
  }
  if (start > 0 && !/\s/.test(text[start - 1])) {
    return null;
  }
  const query = text.slice(start + 1, end);
  if (/\s/.test(query)) {
    return null;
  }
  return { start, end, query };
}

/** Replaces the active `@query` range with the selected candidate. */
export function insertMention(
  text: string,
  range: MentionRange,
  candidate: MentionCandidate,
): InsertedMention {
  const insertion = `@${candidate.name} `;
  const before = text.slice(0, range.start);
  const after = text.slice(range.end);
  return {
    text: `${before}${insertion}${after}`,
    caret: before.length + insertion.length,
  };
}

/**
 * Rebuilds the mention payload from the message body.
 *
 * The body is the single source of truth, so deleting an `@name` also removes
 * it from the routing payload. Candidate names may contain spaces or even `@`
 * (external identities render as `provider@subject`), so matching is done by
 * longest name first instead of a `@(\S+)` regex.
 */
export function mentionsFromText(
  text: string,
  candidates: MentionCandidate[],
): { type: MentionType; id: number; name: string }[] {
  if (!text) {
    return [];
  }
  const ordered = [...candidates].sort(
    (left, right) => right.name.length - left.name.length,
  );
  const found: { type: MentionType; id: number; name: string }[] = [];
  const seen = new Set<string>();
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] !== '@') {
      continue;
    }
    if (index > 0 && !/\s/.test(text[index - 1])) {
      continue;
    }
    const rest = text.slice(index + 1);
    const match = ordered.find(
      (candidate) => candidate.name && rest.startsWith(candidate.name),
    );
    if (!match) {
      continue;
    }
    if (!seen.has(match.key)) {
      seen.add(match.key);
      found.push({ type: match.type, id: match.id, name: match.name });
    }
    index += match.name.length;
  }
  return found;
}
