import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  documentsPage: css`
    display: flex;
    flex-direction: column;
    gap: 14px;
    flex: 1;
    min-height: 0;
    position: relative;
  `,
  documentsHeader: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    position: sticky;
    top: -10px;
    z-index: 6;
    margin: -8px -26px 0;
    padding: 18px 26px 10px;
    background: #f7f6fb;
    box-shadow: 0 -24px 0 #f7f6fb;
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
  documentsBody: css`
    display: flex;
    gap: 16px;
    min-height: 0;
    flex: 1;
  `,
  folderTree: css`
    width: 232px;
    flex: none;
    padding: 8px 4px 16px;
    border-right: 1px solid #f0edf8;
    overflow-y: auto;
    position: sticky;
    top: 92px;
    align-self: flex-start;
    max-height: calc(100vh - 240px);
    background: #f7f6fb;
    z-index: 2;
  `,
  folderRow: css`
    display: flex;
    align-items: center;
    gap: 6px;
    height: 32px;
    padding-right: 6px;
    border-radius: 7px;
    cursor: pointer;
    font-size: 13px;
    color: #3a3358;
    &:hover {
      background: #f7f5fe;
    }
  `,
  folderRowActive: css`
    background: #f0eaff;
    color: #5b3fd4;
  `,
  folderCarets: css`
    width: 12px;
    flex: none;
    font-size: 9px;
    color: #a7a2b8;
  `,
  folderIcon: css`
    font-size: 13px;
    color: #9b93b8;
  `,
  folderName: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  `,
  folderCount: css`
    font-size: 11px;
    color: #a7a2b8;
  `,
  folderHint: css`
    font-size: 10px;
    color: #b7b1c9;
  `,
  folderMore: css`
    opacity: 0;
    .ant-dropdown-trigger:hover &,
    div:hover > & {
      opacity: 1;
    }
  `,
  folderCreate: css`
    margin-top: 10px;
  `,
  wikiContent: css`
    flex: 1;
    min-width: 0;
    padding: 0 4px 24px;
  `,
  wikiBreadcrumb: css`
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    font-size: 12px;
    color: #8b86a0;
    margin-bottom: 10px;
  `,
  wikiPage: css`
    display: flex;
    gap: 20px;
    align-items: flex-start;
  `,
  wikiPageMain: css`
    flex: 1;
    min-width: 0;
  `,
  wikiToc: css`
    width: 190px;
    flex: none;
    position: sticky;
    top: 104px;
    align-self: flex-start;
    max-height: calc(100vh - 220px);
    overflow-y: auto;
    padding: 4px 0 8px 12px;
    border-left: 1px solid #f0edf8;
    font-size: 12px;
    color: #6f6889;
  `,
  wikiTocHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 4px;
    cursor: pointer;
    .ant-btn {
      color: #a7a2b8;
    }
  `,
  wikiTocCollapsed: css`
    width: 36px;
    padding-left: 6px;
    overflow: hidden;
  `,
  wikiTocCollapsedLabel: css`
    display: block;
    margin-top: 10px;
    writing-mode: vertical-rl;
    letter-spacing: 0.12em;
    color: #8b86a0;
    cursor: pointer;
  `,
  commentDock: css`
    position: fixed;
    right: 0;
    top: 92px;
    bottom: 0;
    width: 360px;
    display: flex;
    flex-direction: column;
    background: #fff;
    border-left: 1px solid #eeeaf8;
    border-radius: 12px 0 0 0;
    box-shadow: -14px 0 36px rgba(44, 34, 94, 0.14);
    overflow: hidden;
    z-index: 30;
  `,
  commentDockTab: css`
    position: fixed;
    right: 0;
    bottom: 50%;
    transform: translateY(50%);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 12px 7px;
    background: #fff;
    border: 1px solid #eeeaf8;
    border-right: none;
    border-radius: 10px 0 0 10px;
    box-shadow: -10px 0 26px rgba(44, 34, 94, 0.12);
    writing-mode: vertical-rl;
    font-size: 12px;
    color: #5b3fd4;
    cursor: pointer;
    z-index: 30;
  `,
  commentDockHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 10px 12px;
    font-size: 13px;
    font-weight: 600;
    color: #3a3358;
    cursor: pointer;
    border-bottom: 1px solid #f3f0fb;
    background: #fbfaff;
  `,
  commentDockBody: css`
    padding: 10px 12px 14px;
    overflow-y: auto;
    flex: 1;
  `,
  wikiTocTitle: css`
    font-size: 11px;
    font-weight: 600;
    color: #a7a2b8;
    margin-bottom: 8px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
  `,
  wikiTocItem: css`
    display: block;
    padding: 3px 0;
    color: #6f6889;
    cursor: pointer;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    &:hover {
      color: #5b3fd4;
    }
  `,
  wikiTocLevel2: css`
    padding-left: 10px;
  `,
  wikiTocLevel3: css`
    padding-left: 20px;
  `,
  wikiPageTitle: css`
    font-size: 22px;
    font-weight: 650;
    color: #2f2a45;
    line-height: 1.35;
    margin-bottom: 6px;
    word-break: break-word;
  `,
  wikiComments: css`
    margin-top: 26px;
    padding-top: 16px;
    border-top: 1px solid #f0edf8;
  `,
  wikiCommentsHead: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;
    font-size: 13px;
    font-weight: 600;
    color: #3a3358;
  `,
  commentItem: css`
    padding: 10px 12px;
    border-radius: 9px;
    background: #faf9ff;
    margin-bottom: 8px;
  `,
  commentReply: css`
    margin-left: 24px;
    background: #f6f4fd;
  `,
  commentHead: css`
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: #8b86a0;
    margin-bottom: 5px;
  `,
  commentAuthor: css`
    font-weight: 600;
    color: #3a3358;
  `,
  commentContent: css`
    font-size: 13px;
    line-height: 1.65;
    color: #3a3358;
    white-space: pre-wrap;
    word-break: break-word;
  `,
  commentQuote: css`
    margin: 0 0 6px;
    padding: 4px 8px;
    border-left: 2px solid #cbbcf6;
    background: #f4f0ff;
    font-size: 12px;
    color: #7b7397;
    white-space: pre-wrap;
  `,
  commentActions: css`
    display: flex;
    gap: 10px;
    margin-top: 6px;
    font-size: 12px;
    color: #8b86a0;
    a {
      color: #6a5acd;
      cursor: pointer;
    }
  `,
  commentComposer: css`
    margin-top: 10px;
  `,
  commentReplyHint: css`
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;
    font-size: 12px;
    color: #8b86a0;
  `,
  documentCommentBadge: css`
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 11px;
    color: #8b86a0;
  `,
  documentsFab: css`
    position: absolute;
    right: 24px;
    bottom: 24px;
    box-shadow: 0 8px 20px rgba(91, 63, 212, 0.25);
  `,
  wikiPageHeader: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 8px;
  `,
  wikiPageActions: css`
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    justify-content: flex-end;
    flex: none;
  `,
  wikiFolderOverview: css`
    padding: 4px 2px 24px;
  `,
  wikiPageList: css`
    display: flex;
    flex-direction: column;
    margin-top: 12px;
    border: 1px solid #f0edf8;
    border-radius: 9px;
    overflow: hidden;
  `,
  wikiPageListItem: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 12px;
    border: none;
    border-bottom: 1px solid #f6f4fd;
    background: #fff;
    text-align: left;
    font-size: 13px;
    color: #3a3358;
    cursor: pointer;
    &:last-child {
      border-bottom: none;
    }
    &:hover {
      background: #faf9ff;
    }
  `,
  wikiPageListMeta: css`
    flex: none;
    font-size: 11px;
    color: #a7a2b8;
  `,
}));
