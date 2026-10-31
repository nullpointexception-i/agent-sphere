import {
  cliView,
  docSummary,
  docSummaryZh,
  extractTodos,
  genericSummary,
  progressView,
  readToolResultView,
  searchResultView,
  skillView,
  textContainsView,
  todoProgress,
  toolCallReason,
  toolFamily,
  toolResultText,
  webPageView,
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
        skillId: null,
        stepId: null,
      });
      expect(todos[1].status).toBeUndefined();
    });

    it('keeps skill provenance fields for skill-sourced items', () => {
      const todos = extractTodos({
        args: JSON.stringify({
          todos: [
            {
              content: '1. 打开页面',
              status: 'pending',
              priority: 'medium',
              skillId: 123,
              stepId: 'step1',
            },
          ],
        }),
      });
      expect(todos[0].skillId).toBe(123);
      expect(todos[0].stepId).toBe('step1');
    });

    it('returns empty array for invalid payloads', () => {
      expect(extractTodos({ args: 'not-json' })).toEqual([]);
      expect(extractTodos({})).toEqual([]);
    });
  });

  describe('docSummary', () => {
    it('renders action + document + title', () => {
      const s = docSummary({
        args: JSON.stringify({ action: 'create', title: 'Hello' }),
      });
      expect(s).toBe('create document Hello');
    });

    it('renders action + document when no title', () => {
      const s = docSummary({ args: JSON.stringify({ action: 'update' }) });
      expect(s).toBe('update document');
    });

    it('prefers meta/artifact title and falls back to #documentId', () => {
      expect(
        docSummary({
          args: JSON.stringify({ action: 'read', documentId: 7 }),
          artifact: JSON.stringify({ documentId: 7, title: '季度报告' }),
        }),
      ).toBe('read document 季度报告');
      expect(
        docSummary({
          args: JSON.stringify({ action: 'patch', documentId: 7 }),
          meta: { documentId: 7, title: '季度报告' },
        }),
      ).toBe('patch document 季度报告');
      expect(
        docSummary({ args: JSON.stringify({ action: 'read', documentId: 7 }) }),
      ).toBe('read document #7');
    });
  });

  describe('genericSummary', () => {
    it('extracts query as subject', () => {
      const s = genericSummary({
        args: JSON.stringify({ action: 'search', query: 'a b' }),
      });
      expect(s).toBe('search a b');
    });

    it('falls back to displayName', () => {
      expect(genericSummary({ displayName: '工具' })).toBe('工具');
    });

    it('does not repeat the raw tool name as the summary', () => {
      expect(
        genericSummary({ displayName: '文本包含判断', toolName: 'builtin_11' }),
      ).toBe('文本包含判断');
    });
  });

  describe('textContainsView', () => {
    it('reports a hit with needle, mode and target size', () => {
      const view = textContainsView({
        args: JSON.stringify({
          needle: 'hello',
          haystack: 'hello world',
          mode: 'IGNORE_CASE',
        }),
        artifact: JSON.stringify({ contains: true }),
      });
      expect(view).toEqual({
        hit: true,
        needle: 'hello',
        mode: 'IGNORE_CASE',
        haystackChars: 11,
        error: null,
      });
    });

    it('reports a miss and defaults the mode to EXACT', () => {
      const view = textContainsView({
        args: JSON.stringify({ needle: 'x', haystack: 'abc' }),
        artifact: JSON.stringify({ contains: false }),
      });
      expect(view.hit).toBe(false);
      expect(view.mode).toBe('EXACT');
    });

    it('stays unknown for malformed artifacts', () => {
      const view = textContainsView({ args: 'not-json', artifact: '{}' });
      expect(view.hit).toBeNull();
      expect(view.needle).toBe('');
      expect(view.error).toBeNull();
    });

    it('surfaces tool errors instead of a boolean result', () => {
      const view = textContainsView({
        args: JSON.stringify({ needle: '', haystack: 'abc' }),
        artifact: JSON.stringify({ error: 'needle 不能为空或纯空白' }),
      });
      expect(view.hit).toBeNull();
      expect(view.error).toBe('needle 不能为空或纯空白');
    });
  });

  describe('webPageView', () => {
    it('summarizes title, status and body length without the body', () => {
      const view = webPageView({
        args: JSON.stringify({ url: 'https://example.com/a' }),
        artifact: JSON.stringify({
          title: 'Example',
          statusCode: 200,
          markdown: '# hello',
        }),
      });
      expect(view.title).toBe('Example');
      expect(view.statusCode).toBe(200);
      expect(view.chars).toBe(7);
      expect(view.error).toBeNull();
    });

    it('surfaces errorMessage', () => {
      const view = webPageView({
        artifact: JSON.stringify({ errorMessage: 'fetch failed' }),
      });
      expect(view.error).toBe('fetch failed');
    });
  });

  describe('skillView', () => {
    it('uses result total and skill names for list', () => {
      const view = skillView({
        args: JSON.stringify({ action: 'list' }),
        artifact: JSON.stringify({
          preview: 'Found 9 skill(s)',
          total: 9,
          skills: [{ name: 'a' }, { name: 'b' }],
        }),
      });
      expect(view.action).toBe('list');
      expect(view.total).toBe(9);
      expect(view.names).toEqual(['a', 'b']);
    });
  });

  describe('readToolResultView', () => {
    it('reports range and truncation', () => {
      const view = readToolResultView({
        args: JSON.stringify({ callId: 'call_1' }),
        artifact: JSON.stringify({
          callId: 'call_1',
          toolName: 'builtin_4',
          totalChars: 100,
          offset: 0,
          nextOffset: 50,
          truncated: true,
        }),
      });
      expect(view.callId).toBe('call_1');
      expect(view.totalChars).toBe(100);
      expect(view.nextOffset).toBe(50);
      expect(view.truncated).toBe(true);
    });
  });

  describe('progressView', () => {
    it('reads summary and clamps percent', () => {
      const view = progressView({
        args: JSON.stringify({ summary: 'half', percent: 160 }),
      });
      expect(view.summary).toBe('half');
      expect(view.percent).toBe(100);
    });
  });

  describe('cliView', () => {
    it('extracts exit code and output', () => {
      const view = cliView({
        artifact: JSON.stringify({ exitCode: 1, stdout: '', stderr: 'boom' }),
      });
      expect(view.exitCode).toBe(1);
      expect(view.stderr).toBe('boom');
    });
  });

  describe('docSummaryZh', () => {
    it('localizes document actions', () => {
      expect(
        docSummaryZh({
          args: JSON.stringify({ action: 'create', title: 'Hello' }),
        }),
      ).toBe('创建文档 Hello');
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

  describe('searchResultView', () => {
    it('extracts title/host/summary from a results array', () => {
      const results = searchResultView({
        artifact: JSON.stringify({
          results: [
            {
              title: 'Agent runtime',
              url: 'https://example.com/docs/runtime',
              snippet: 'How the agent runtime works',
            },
          ],
        }),
      });
      expect(results).toEqual([
        {
          title: 'Agent runtime',
          url: 'https://example.com/docs/runtime',
          host: 'example.com',
          summary: 'How the agent runtime works',
        },
      ]);
    });

    it('returns empty list for non-search payloads', () => {
      expect(
        searchResultView({ artifact: JSON.stringify({ status: 'ok' }) }),
      ).toEqual([]);
    });

    it('unwraps MCP content[].text JSON payloads', () => {
      const content = {
        artifact: JSON.stringify({
          content: [
            {
              type: 'text',
              text: JSON.stringify([
                {
                  title: 'Wrapped',
                  url: 'https://wrapped.example/x',
                  description: 'from text',
                },
              ]),
            },
          ],
        }),
      };
      expect(searchResultView(content)).toEqual([
        {
          title: 'Wrapped',
          url: 'https://wrapped.example/x',
          host: 'wrapped.example',
          summary: 'from text',
        },
      ]);
      expect(toolResultText(content)).toContain('Wrapped');
    });
  });

  describe('toolCallReason', () => {
    it('prefers meta.reason and falls back to args._reason', () => {
      expect(toolCallReason({ meta: { reason: ' 检查页面 ' } })).toBe(
        '检查页面',
      );
      expect(
        toolCallReason({ args: JSON.stringify({ _reason: '读取文档' }) }),
      ).toBe('读取文档');
      expect(toolCallReason({})).toBe('');
    });
  });
});
