import {
  arrangeMentionRows,
  buildMentionCandidates,
  detectMentionQuery,
  filterMentionCandidates,
  insertMention,
  type MentionCandidate,
  mentionsFromText,
} from '../mentionModel';

const AGENT: MentionCandidate = {
  key: 'AGENT:7',
  type: 'AGENT',
  id: 7,
  name: 'Research Bot',
};
const MEMBER: MentionCandidate = {
  key: 'USER:9',
  type: 'USER',
  id: 9,
  name: 'bole@elvin',
};
const CANDIDATES = [AGENT, MEMBER];

describe('detectMentionQuery', () => {
  it('opens at the start of the text', () => {
    expect(detectMentionQuery('@res', 4)).toMatchObject({
      start: 0,
      query: 'res',
    });
  });

  it('opens after whitespace', () => {
    expect(detectMentionQuery('hi @res', 7)).toMatchObject({
      start: 3,
      query: 'res',
    });
  });

  it('ignores an e-mail-like token', () => {
    expect(detectMentionQuery('mail me@example.com', 19)).toBeNull();
  });

  it('closes once the query contains whitespace', () => {
    expect(detectMentionQuery('@res bot', 8)).toBeNull();
  });

  it('uses the caret, not the end of the text', () => {
    expect(detectMentionQuery('@res tail', 3)).toMatchObject({ query: 're' });
  });

  it('returns null when there is no @', () => {
    expect(detectMentionQuery('hello', 5)).toBeNull();
  });
});

describe('buildMentionCandidates', () => {
  it('includes agents and members, keeps owners and marks the current user', () => {
    const candidates = buildMentionCandidates(
      [
        {
          participantType: 'AGENT',
          participantId: 7,
          displayName: 'Research Bot',
          ownerUserId: 8,
        },
        { participantType: 'USER', participantId: 8, displayName: 'Celine' },
        {
          participantType: 'USER',
          participantId: 9,
          ssoProviderCode: 'bole',
          ssoSubject: 'elvin',
        },
        { participantType: 'SYSTEM', participantId: 10 },
      ],
      8,
    );
    expect(candidates.map((candidate) => candidate.name)).toEqual([
      'Research Bot',
      'bole@elvin',
      'Celine',
    ]);
    expect(candidates[0]).toMatchObject({ type: 'AGENT', ownerId: 8 });
    expect(candidates[1]).toMatchObject({ type: 'USER', isSelf: false });
    expect(candidates[2]).toMatchObject({ type: 'USER', isSelf: true });
  });

  it('de-duplicates the same participant', () => {
    const candidates = buildMentionCandidates(
      [
        {
          participantType: 'AGENT',
          participantId: 7,
          displayName: 'Research Bot',
        },
        {
          participantType: 'AGENT',
          participantId: 7,
          displayName: 'Research Bot',
        },
      ],
      null,
    );
    expect(candidates).toHaveLength(1);
  });
});

describe('filterMentionCandidates', () => {
  it('matches case-insensitively', () => {
    expect(filterMentionCandidates(CANDIDATES, 'RESEARCH')).toEqual([AGENT]);
  });

  it('lists everything for an empty query', () => {
    expect(filterMentionCandidates(CANDIDATES, '')).toHaveLength(2);
  });
});

describe('arrangeMentionRows', () => {
  const owner: MentionCandidate = {
    key: 'USER:8',
    type: 'USER',
    id: 8,
    name: 'Celine',
    isSelf: true,
  };
  const other: MentionCandidate = {
    key: 'USER:9',
    type: 'USER',
    id: 9,
    name: 'bole@elvin',
  };
  const owned: MentionCandidate = {
    key: 'AGENT:7',
    type: 'AGENT',
    id: 7,
    name: 'Daily Assistant',
    ownerId: 8,
  };
  const orphan: MentionCandidate = {
    key: 'AGENT:6',
    type: 'AGENT',
    id: 6,
    name: 'Shared Assistant',
    ownerId: 99,
  };

  it('nests every avatar under its owner and puts the current user first', () => {
    const rows = arrangeMentionRows([owned, other, owner, orphan], '');
    expect(rows.map((row) => [row.candidate.name, row.depth])).toEqual([
      ['Celine', 0],
      ['Daily Assistant', 1],
      ['bole@elvin', 0],
      ['Shared Assistant', 0],
    ]);
    expect(rows[0].hasChildren).toBe(true);
  });

  it('keeps the owner row when only an avatar matches the query', () => {
    const rows = arrangeMentionRows([owned, other, owner, orphan], 'Daily');
    expect(rows.map((row) => [row.candidate.name, row.depth])).toEqual([
      ['Celine', 0],
      ['Daily Assistant', 1],
    ]);
  });

  it('returns nothing when no candidate matches', () => {
    expect(arrangeMentionRows([owned, other, owner], 'zzz')).toEqual([]);
  });
});

describe('insertMention', () => {
  it('replaces the typed query and places the caret after the name', () => {
    const result = insertMention(
      'hi @re tail',
      { start: 3, end: 6, query: 're' },
      AGENT,
    );
    expect(result.text).toBe('hi @Research Bot  tail');
    expect(result.caret).toBe('hi @Research Bot '.length);
  });
});

describe('mentionsFromText', () => {
  it('prefers the longest matching name', () => {
    const short: MentionCandidate = {
      key: 'AGENT:1',
      type: 'AGENT',
      id: 1,
      name: 'Bo',
    };
    const long: MentionCandidate = {
      key: 'AGENT:2',
      type: 'AGENT',
      id: 2,
      name: 'Bo Lee',
    };
    expect(mentionsFromText('@Bo Lee please', [short, long])).toEqual([
      { type: 'AGENT', id: 2, name: 'Bo Lee' },
    ]);
  });

  it('matches names that contain @', () => {
    expect(mentionsFromText('ask @bole@elvin now', CANDIDATES)).toEqual([
      { type: 'USER', id: 9, name: 'bole@elvin' },
    ]);
  });

  it('de-duplicates repeated mentions', () => {
    expect(
      mentionsFromText('@Research Bot and @Research Bot', CANDIDATES),
    ).toHaveLength(1);
  });

  it('ignores mentions inside a word', () => {
    expect(mentionsFromText('mail@Research Bot', CANDIDATES)).toEqual([]);
  });

  it('drops ids when the text no longer contains the name', () => {
    expect(mentionsFromText('hello there', CANDIDATES)).toEqual([]);
  });
});
