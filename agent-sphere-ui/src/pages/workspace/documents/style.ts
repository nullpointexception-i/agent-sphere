import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  documentsPage: css`
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-height: 0;
  `,
  documentsHeader: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  `,
  documentsTitle: css`
    font-size: 18px;
    font-weight: 600;
    color: #2f2850;
  `,
  documentsSubtitle: css`
    margin-top: 4px;
    font-size: 12px;
    color: #8b86a0;
  `,
  documentsToolbar: css`
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  `,
  documentsSearch: css`
    width: 280px;
  `,
  documentsGrid: css`
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 14px;
    align-content: start;
    padding: 4px 2px 8px;
  `,
  documentCard: css`
    position: relative;
    display: flex;
    flex-direction: column;
    min-width: 0;
    border: 1px solid #eceaf5;
    border-radius: 12px;
    background: #fff;
    box-shadow: 0 6px 18px rgba(44, 34, 94, 0.04);
    transition: transform 0.15s ease, box-shadow 0.15s ease;
    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 10px 24px rgba(44, 34, 94, 0.09);
    }
  `,
  documentCardButton: css`
    display: flex;
    flex-direction: column;
    gap: 8px;
    align-items: center;
    min-width: 0;
    padding: 18px 12px 12px;
    border: none;
    border-radius: 12px;
    background: transparent;
    cursor: pointer;
    font: inherit;
    color: inherit;
    text-align: center;
    &:focus-visible {
      outline: 2px solid #7c6cff;
      outline-offset: -2px;
    }
  `,
  documentCardTop: css`
    position: absolute;
    top: 6px;
    right: 6px;
    z-index: 2;
    display: flex;
    align-items: center;
    gap: 2px;
  `,
  documentMore: css`
    border: none;
    background: transparent;
    color: #9b95b3;
    cursor: pointer;
    padding: 2px 4px;
    border-radius: 6px;
    &:hover {
      background: #f4f1ff;
      color: #6c5ce7;
    }
  `,
  documentIconWrap: css`
    display: flex;
    align-items: center;
    justify-content: center;
    height: 76px;
    width: 100%;
  `,
  documentName: css`
    width: 100%;
    text-align: center;
    font-size: 13px;
    line-height: 1.35;
    color: #3a3358;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    word-break: break-word;
  `,
  documentMeta: css`
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 2px;
    align-items: center;
    font-size: 11px;
    color: #9b95b3;
  `,
  documentMetaRow: css`
    display: flex;
    align-items: center;
    gap: 4px;
  `,
  documentReadonly: css`
    color: #b7791f;
    font-size: 10px;
    display: inline-flex;
    align-items: center;
    gap: 3px;
  `,
  documentsState: css`
    flex: 1;
    min-height: 220px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    gap: 10px;
  `,
  documentsLoadMore: css`
    display: flex;
    justify-content: center;
    padding: 4px 0 12px;
  `,
  documentPreviewMeta: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px 16px;
    padding: 10px 0 14px;
    margin-bottom: 10px;
    border-bottom: 1px solid #f0edf8;
    font-size: 12px;
    color: #8b86a0;
  `,
  documentPreviewText: css`
    white-space: pre-wrap;
    word-break: break-word;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
    line-height: 1.7;
    color: #3a3358;
  `,
  documentPreviewActions: css`
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  `,
}));
