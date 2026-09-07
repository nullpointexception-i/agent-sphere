import { createElement } from 'react';
import { marked } from 'marked';
import type { ReactNode } from 'react';

/**
 * Markdown → React renderer built on `marked`'s tokenizer (GFM, incl. tables).
 * Renders to React nodes directly (never dangerouslySetInnerHTML) so LLM output
 * can't inject HTML — everything it emits is text/React elements.
 */

function renderInlineTokens(tokens: any[]): ReactNode {
  return tokens.length === 0
    ? null
    : tokens.map((t, i) => {
        switch (t.type) {
          case 'text':
            return <span key={i}>{t.text}</span>;
          case 'escape':
            return <span key={i}>{t.text}</span>;
          case 'strong':
            return <strong key={i}>{renderInlineTokens(t.tokens ?? [])}</strong>;
          case 'em':
            return <em key={i}>{renderInlineTokens(t.tokens ?? [])}</em>;
          case 'del':
            return <del key={i}>{renderInlineTokens(t.tokens ?? [])}</del>;
          case 'codespan':
            return (
              <code key={i} className="aw-md-codespan">
                {t.text}
              </code>
            );
          case 'br':
            return <br key={i} />;
          case 'link': {
            const href = t.href || '';
            return (
              <a
                key={i}
                href={href}
                target="_blank"
                rel="noreferrer"
                className="aw-md-link"
              >
                {renderInlineTokens(t.tokens ?? [t])}
              </a>
            );
          }
          case 'image':
            return (
              <img
                key={i}
                src={t.href}
                alt={t.text || ''}
                title={t.title || ''}
                loading="lazy"
                className="aw-md-img"
              />
            );
          case 'html':
            // Raw HTML from LLM output: render as escaped text, never live DOM.
            return <span key={i}>{t.raw ?? ''}</span>;
          default:
            return <span key={i}>{t.text ?? t.raw ?? ''}</span>;
        }
      });
}

function renderChildren(tokens: any[]): ReactNode {
  const out: ReactNode[] = [];
  const push = (node: ReactNode) => {
    if (node !== null && node !== undefined && node !== '') out.push(node);
  };
  for (const t of tokens) {
    switch (t.type) {
      case 'space':
        break;
      case 'heading':
        push(
          <HeadingBlock key={`${t.raw}-${out.length}`} level={t.depth} tokens={t.tokens ?? []} />,
        );
        break;
      case 'paragraph':
        push(
          <p key={`${t.raw}-${out.length}`} className="aw-md-p">
            {renderInlineTokens(t.tokens ?? [])}
          </p>,
        );
        break;
      case 'text':
        push(<p key={`${t.raw}-${out.length}`}>{t.text}</p>);
        break;
      case 'code':
        push(
          <pre key={`${t.raw}-${out.length}`} className="aw-md-pre">
            <code className="aw-md-code">{t.text}</code>
          </pre>,
        );
        break;
      case 'blockquote':
        push(
          <blockquote key={`${t.raw}-${out.length}`} className="aw-md-quote">
            {renderChildren(t.tokens ?? [])}
          </blockquote>,
        );
        break;
      case 'list':
        push(<ListBlock key={`${t.raw}-${out.length}`} token={t} />);
        break;
      case 'table':
        push(<TableBlock key={`${t.raw}-${out.length}`} token={t} />);
        break;
      case 'hr':
        push(<hr key={`hr-${out.length}`} className="aw-md-hr" />);
        break;
      case 'html':
        push(<span key={`${t.raw}-${out.length}`}>{t.raw ?? ''}</span>);
        break;
      default:
        push(<span key={`${t.raw}-${out.length}`}>{t.raw ?? t.text ?? ''}</span>);
    }
  }
  return out;
}

function HeadingBlock({ level, tokens }: { level: number; tokens: any[] }) {
  const tag = `h${Math.min(Math.max(level, 1), 6)}`;
  return createElement(tag, { className: 'aw-md-heading' }, renderInlineTokens(tokens));
}

function ListBlock({ token }: { token: any }) {
  const ordered = token.ordered === true;
  const items = token.items ?? [];
  const body = items.map((it: any, idx: number) => {
    const content = it.tokens ? renderChildren(it.tokens) : it.text;
    if (it.task) {
      return (
        <li key={idx} className="aw-md-li aw-md-task">
          <input type="checkbox" checked={it.checked === true} readOnly disabled />
          <span>{content}</span>
        </li>
      );
    }
    return (
      <li key={idx} className="aw-md-li">
        {content}
      </li>
    );
  });
  if (ordered) {
    return (
      <ol key={token.raw} className="aw-md-ol" start={token.start ? Number(token.start) : undefined}>
        {body}
      </ol>
    );
  }
  return (
    <ul key={token.raw} className="aw-md-ul">
      {body}
    </ul>
  );
}

function TableBlock({ token }: { token: any }) {
  const header = (token.header ?? []).map((cell: any, i: number) => (
    <th key={i} align={cell.align ?? undefined}>
      {renderInlineTokens(cell.tokens ?? [])}
    </th>
  ));
  const rows = (token.rows ?? []).map((row: any[], r: number) => (
    <tr key={r}>
      {row.map((cell: any, c: number) => (
        <td key={c} align={cell.align ?? undefined}>
          {renderInlineTokens(cell.tokens ?? [])}
        </td>
      ))}
    </tr>
  ));
  return (
    <div className="aw-md-table-wrap">
      <table className="aw-md-table">
        <thead>
          <tr>{header}</tr>
        </thead>
        <tbody>{rows}</tbody>
      </table>
    </div>
  );
}

export function Markdown({ text }: { text: string }) {
  const tokens = marked.lexer(text ?? '', { gfm: true }) as any[];
  return <div className="aw-md">{renderChildren(tokens)}</div>;
}