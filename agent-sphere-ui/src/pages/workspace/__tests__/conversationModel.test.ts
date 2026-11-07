import type { ImConversation, ImMessage } from '@/services/agentSphere/im';
import {
  conversationAvatar,
  conversationDisplayName,
  conversationPreview,
  isSameUser,
  isSelfConversation,
  isSelfParticipants,
  isSelfUser,
  listTime,
  mergeConversation,
  mergeMessages,
  messageTime,
  otherUserParticipant,
  sortConversations,
  taskCardOf,
  taskStatusLabel,
} from '../conversationModel';

const me = { id: 1, username: 'alice', displayName: 'Alice' };

function conversation(partial: Partial<ImConversation>): ImConversation {
  return {
    id: 1,
    type: 'DM',
    participants: [],
    ...partial,
  } as ImConversation;
}

function message(id: number, seq: number, content: any): ImMessage {
  return { id, seq, content } as ImMessage;
}

describe('conversationModel', () => {
  it('matches users by username first, then id', () => {
    expect(isSameUser({ username: 'alice', participantId: 9 } as any, me)).toBe(
      true,
    );
    expect(isSameUser({ participantId: 1 } as any, me)).toBe(true);
    expect(isSameUser({ participantId: 2 } as any, me)).toBe(false);
    expect(isSameUser(undefined, me)).toBe(false);
    expect(isSelfUser({ username: 'alice' }, me)).toBe(true);
  });

  it('detects self conversations', () => {
    const self = conversation({
      participants: [
        { participantType: 'USER', username: 'alice', participantId: 1 } as any,
      ],
    });
    expect(isSelfConversation(self, me)).toBe(true);
    expect(isSelfConversation(conversation({ type: 'GROUP', id: 2 }), me)).toBe(
      false,
    );
  });

  it('resolves display names per conversation type', () => {
    expect(
      conversationDisplayName(
        conversation({ type: 'GROUP', title: 'Team' }),
        me,
      ),
    ).toBe('Team');
    expect(
      conversationDisplayName(
        conversation({
          participants: [
            {
              participantType: 'USER',
              username: 'bob',
              participantId: 2,
            } as any,
          ],
        }),
        me,
      ),
    ).toBe('Direct chat');
    expect(
      conversationDisplayName(conversation({ selfConversation: true }), me),
    ).toBe('Myself');
  });

  it('builds previews from task cards and text', () => {
    expect(
      conversationPreview(
        conversation({
          lastMessage: message(1, 1, {
            cardType: 'ROUTER_TASK',
            title: 'Task A',
          }),
        }),
      ),
    ).toBe('Task A');
    expect(
      conversationPreview(
        conversation({ lastMessage: message(2, 2, { text: 'hello' }) }),
      ),
    ).toBe('hello');
  });

  it('parses task cards and labels statuses', () => {
    expect(taskCardOf(message(1, 1, { text: 'x' }))).toBeNull();
    expect(taskCardOf(message(2, 2, { cardType: 'ROUTER_TASK' }))).toEqual({
      cardType: 'ROUTER_TASK',
    });
    expect(taskStatusLabel('DONE')).toBe('Completed');
    expect(taskStatusLabel('PENDING')).toBe('Awaiting reply');
    expect(taskStatusLabel('WEIRD')).toBe('WEIRD');
  });

  it('sorts pinned conversations first, then by last message time', () => {
    const a = conversation({
      id: 1,
      pinned: true,
      lastMessageAt: '2024-01-01',
    });
    const b = conversation({ id: 2, lastMessageAt: '2024-02-01' });
    expect(sortConversations([b, a]).map((c) => c.id)).toEqual([1, 2]);
  });

  it('merges conversations without duplicates', () => {
    const existing = conversation({ id: 1, title: 'Old' });
    const updated = conversation({ id: 1, title: 'New' });
    const added = conversation({ id: 2, title: 'Second' });
    expect(mergeConversation([existing], updated)).toHaveLength(1);
    expect(mergeConversation([existing], updated)[0].title).toBe('New');
    expect(mergeConversation([existing], added)).toHaveLength(2);
  });

  it('formats list and message times defensively', () => {
    expect(listTime(undefined)).toBe('');
    expect(listTime('not-a-date')).toBe('');
    expect(listTime('2024-01-01 10:00:00')).toBeTruthy();
    expect(messageTime(undefined)).toBe('');
    expect(messageTime('2024-01-01 10:00:00')).toBe('10:00');
  });

  it('merges message pages by id and orders by seq', () => {
    const merged = mergeMessages(
      [message(1, 1, { text: 'a' }), message(2, 2, { text: 'b' })],
      [message(2, 2, { text: 'b2' }), message(3, 3, { text: 'c' })],
    );
    expect(merged.map((m) => m.id)).toEqual([1, 2, 3]);
    expect((merged[1].content as any).text).toBe('b2');
  });

  it('detects self participants and group exclusions', () => {
    expect(
      isSelfParticipants(
        [{ participantType: 'USER', username: 'alice' } as any],
        me,
      ),
    ).toBe(true);
    expect(
      isSelfParticipants(
        [
          { participantType: 'USER', username: 'alice' } as any,
          { participantType: 'AGENT', participantId: 9 } as any,
        ],
        me,
      ),
    ).toBe(false);
    expect(
      isSelfConversation(conversation({ selfConversation: false }), me),
    ).toBe(false);
  });

  it('resolves the other participant and avatar', () => {
    const dm = conversation({
      participants: [
        { participantType: 'USER', username: 'alice', participantId: 1 } as any,
        {
          participantType: 'USER',
          username: 'bob',
          participantId: 2,
          avatar: 'bob.png',
        } as any,
      ],
    });
    expect(otherUserParticipant(dm, me)?.username).toBe('bob');
    expect(conversationAvatar(dm, me)).toBe('bob.png');
    expect(
      otherUserParticipant(conversation({ type: 'GROUP' }), me),
    ).toBeUndefined();
  });

  it('labels every known task status', () => {
    expect(taskStatusLabel('QUEUED')).toBe('Queued');
    expect(taskStatusLabel('DISPATCHING')).toBe('Preparing');
    expect(taskStatusLabel('RUNNING')).toBe('Running');
    expect(taskStatusLabel('WAITING')).toBe('Waiting');
    expect(taskStatusLabel('COMPLETED')).toBe('Completed');
    expect(taskStatusLabel('FAILED')).toBe('Failed');
    expect(taskStatusLabel('CANCELLED')).toBe('Cancelled');
    expect(taskStatusLabel('ANSWERED')).toBe('Answered');
    expect(taskStatusLabel(undefined)).toBe('Unknown');
  });

  it('formats older timestamps by year', () => {
    expect(listTime('2001-02-03 04:05:06')).toBe('2001/2/3');
  });
});
