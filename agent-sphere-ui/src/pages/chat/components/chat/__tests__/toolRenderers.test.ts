import {
  docSummary,
  extractTodos,
  genericSummary,
  todoProgress,
  toolFamily,
} from '../toolRenderers';

describe('toolRenderers', () => {
  describe('toolFamily', () => {
    it('detects todo family from todos array in args', () => {
      const content = {
        displayName: '待办写入',
        args: JSON.stringify({
          todos: [{ content: 'a', status: 'pending', priority: 'high' }],
        }),
      };
      expect(toolFamily(content)).toBe('todo');
    });

    it('detects doc family from action+title args', () => {
      const content = {
        displayName: '文档操作',
        args: JSON.stringify({ action: 'create', title: 'My doc' }),
      };
      expect(toolFamily(content)).toBe('doc');
    });

    it('falls back to doc via displayName keyword', () => {
      const content: any = { displayName: '文档操作' };
      expect(toolFamily(content)).toBe('doc');
    });

    it('returns other for unrelated args', () => {
      const content: any = {
        args: JSON.stringify({ query: 'hello' }),
      };
      expect(toolFamily(content)).toBe('other');
    });
  });

  describe('extractTodos', () => {
    it('parses todos from args with normalized fields', () => {
      const todos = extractTodos({
        args: JSON.stringify({
          todos: [
            { content: 'a', status: 'completed', priority: 'high' },
            { content: 'b' },
          ],
        }),
      });
      expect(todos).toHaveLength(2);
      expect(todos[0]).toEqual({
        content: 'a',
        status: 'completed',
        priority: 'high',
      });
      expect(todos[1].status).toBeUndefined();
    });

    it('returns empty array for invalid payloads', () => {
      expect(extractTodos({ args: 'not-json' })).toEqual([]);
      expect(extractTodos({})).toEqual([]);
    });
  });

  describe('docSummary', () => {
    it('renders action + document + title', () => {
      const s = docSummary({ args: JSON.stringify({ action: 'create', title: 'Hello' }) });
      expect(s).toBe('create document Hello');
    });

    it('renders action + document when no title', () => {
      const s = docSummary({ args: JSON.stringify({ action: 'update' }) });
      expect(s).toBe('update document');
    });
  });

  describe('genericSummary', () => {
    it('extracts query as subject', () => {
      const s = genericSummary({ args: JSON.stringify({ action: 'search', query: 'a b' }) });
      expect(s).toBe('search a b');
    });

    it('falls back to displayName', () => {
      expect(genericSummary({ displayName: '工具' })).toBe('工具');
    });
  });

  describe('todoProgress', () => {
    it('counts completed vs total', () => {
      const todos = extractTodos({
        args: JSON.stringify({
          todos: [
            { content: 'a', status: 'completed' },
            { content: 'b', status: 'pending' },
            { content: 'c', status: 'in_progress' },
          ],
        }),
      });
      expect(todoProgress(todos)).toEqual({ done: 1, total: 3 });
    });
  });
});
