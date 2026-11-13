import { createStyles } from 'antd-style';

export const useAppStyles = createStyles(({ css }) => ({
  shell: css`
    display: flex;
    flex-direction: column;
    gap: 18px;
    min-height: 100%;
  `,
  pageHead: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  `,
  title: css`
    font-size: 18px;
    font-weight: 500;
    color: #241b4d;
  `,
  subtitle: css`
    margin-top: 4px;
    color: #8b86a0;
    font-size: 12px;
  `,
  cardGrid: css`
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 14px;
  `,
  card: css`
    min-height: 132px;
    padding: 16px;
    border: 1px solid #efedf7;
    border-radius: 12px;
    background: #fff;
    cursor: pointer;
    transition: box-shadow 0.15s ease, transform 0.15s ease;
    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 10px 24px rgba(44, 34, 94, 0.08);
    }
  `,
  cardTop: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  `,
  cardTitle: css`
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 500;
    color: #241b4d;
  `,
  cardDesc: css`
    margin-top: 10px;
    color: #8b86a0;
    font-size: 12px;
    line-height: 1.6;
    min-height: 38px;
  `,
  cardActions: css`
    margin-top: 12px;
    display: flex;
    justify-content: flex-end;
  `,
  sectionLabel: css`
    margin-top: 4px;
    font-size: 12px;
    font-weight: 500;
    color: #6f6885;
  `,
  toolbar: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 16px;
  `,
  list: css`
    display: flex;
    flex-direction: column;
    gap: 10px;
  `,
  projectGrid: css`
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
    gap: 12px;
  `,
  projectCard: css`
    display: flex;
    flex-direction: column;
    min-height: 168px;
    padding: 14px;
    border: 1px solid #efedf7;
    border-radius: 12px;
    background: #fff;
    cursor: pointer;
    transition: box-shadow 0.15s ease, transform 0.15s ease;
    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 8px 20px rgba(44, 34, 94, 0.08);
    }
  `,
  projectCardTop: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
  `,
  projectCardTitle: css`
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 14px;
    font-weight: 600;
    color: #241b4d;
  `,
  projectCardKey: css`
    margin-top: 4px;
    overflow: hidden;
    color: #8b86a0;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  projectCardDesc: css`
    flex: 1;
    margin-top: 10px;
    color: #6f6a86;
    font-size: 12px;
    line-height: 1.5;
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  `,
  richEditor: css`
    border: 1px solid #efedf7;
    border-radius: 10px;
    overflow: hidden;
    background: #fff;
    .project-rich-content {
      min-height: 120px;
      padding: 10px 12px;
      outline: none;
      color: #241b4d;
      font-size: 13px;
      line-height: 1.6;
    }
    .project-rich-content p {
      margin: 0 0 6px;
    }
    .project-rich-content ul,
    .project-rich-content ol {
      margin: 0 0 6px;
      padding-left: 20px;
    }
    .project-rich-content blockquote {
      margin: 0 0 6px;
      padding-left: 10px;
      border-left: 3px solid #e6e2f2;
      color: #6f6a86;
    }
    .project-rich-content img {
      max-width: 100%;
      border-radius: 6px;
    }
  `,
  richEditorToolbar: css`
    padding: 6px 8px;
    border-bottom: 1px solid #f1eff8;
  `,
  richContent: css`
    flex: 1;
    max-height: 72px;
    margin-top: 10px;
    overflow: hidden;
    color: #6f6a86;
    font-size: 12px;
    line-height: 1.5;
    p {
      margin: 0 0 4px;
    }
    ul,
    ol {
      margin: 0 0 4px;
      padding-left: 18px;
    }
    h1,
    h2,
    h3 {
      margin: 0 0 4px;
      color: #241b4d;
      font-size: 13px;
    }
    blockquote {
      margin: 0 0 4px;
      padding-left: 8px;
      border-left: 2px solid #e6e2f2;
    }
    img {
      max-width: 100%;
      border-radius: 6px;
      cursor: zoom-in;
    }
  `,
  richContentExpanded: css`
    flex: none;
    max-height: none;
    overflow: visible;
    font-size: 13px;
    line-height: 1.7;
  `,
  detailMetaTags: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    min-width: 0;
    & .ant-tag {
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  `,
  detailMetaLine: css`
    display: flex;
    flex-wrap: wrap;
    gap: 4px 16px;
    margin-top: 10px;
    color: #8b86a0;
    font-size: 12px;
    line-height: 1.6;
  `,
  commentList: css`
    display: flex;
    flex-direction: column;
    height: 240px;
    overflow-y: auto;
    padding-right: 4px;
    & .ant-empty {
      margin: auto;
    }
  `,
  commentItem: css`
    padding: 10px 0;
    border-bottom: 1px solid #f3f0fb;
  `,
  commentMeta: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    color: #8b86a0;
    font-size: 11px;
  `,
  commentContent: css`
    margin-top: 6px;
    color: #3a3358;
    font-size: 13px;
    line-height: 1.6;
    white-space: pre-wrap;
  `,
  commentAttachments: css`
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 4px;
  `,
  commentAttachmentList: css`
    display: flex;
    flex-wrap: wrap;
    align-self: stretch;
    gap: 4px;
  `,
  commentComposer: css`
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 16px;
    align-items: stretch;
  `,
  commentComposerActions: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  `,
  requirementDetailPage: css`
    display: flex;
    align-items: flex-start;
    gap: 28px;
  `,
  requirementDetailMain: css`
    flex: 1;
    min-width: 0;
  `,
  requirementDetailHeading: css`
    display: flex;
    align-items: flex-start;
    gap: 10px;
    margin-bottom: 12px;
  `,
  requirementDetailTitleBlock: css`
    flex: 1;
    min-width: 0;
  `,
  requirementDetailTitle: css`
    color: #241b4d;
    font-size: 21px;
    font-weight: 600;
    line-height: 1.35;
  `,
  requirementDetailSubtitle: css`
    margin-top: 4px;
    color: #8b86a0;
    font-size: 13px;
  `,
  requirementDetailAside: css`
    width: 270px;
    flex-shrink: 0;
    padding-left: 20px;
    border-left: 1px solid #efedf7;
  `,
  requirementSideField: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    padding: 6px 0;
    font-size: 13px;
  `,
  requirementSideLabel: css`
    flex-shrink: 0;
    color: #8b86a0;
  `,
  requirementSideValue: css`
    min-width: 0;
    overflow: hidden;
    color: #3a3358;
    text-align: right;
    text-overflow: ellipsis;
  `,
  requirementSideSectionTitle: css`
    margin-bottom: 6px;
    color: #241b4d;
    font-size: 13px;
    font-weight: 600;
  `,
  requirementCard: css`
    display: flex;
    flex-direction: column;
    min-height: 176px;
    padding: 14px;
    border: 1px solid #efedf7;
    border-radius: 12px;
    background: #fff;
    cursor: pointer;
    transition: box-shadow 0.15s ease, transform 0.15s ease;
    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 8px 20px rgba(44, 34, 94, 0.08);
    }
  `,
  requirementCardTop: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
  `,
  requirementCardProject: css`
    overflow: hidden;
    color: #8b86a0;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  requirementCardTitle: css`
    margin-top: 8px;
    color: #241b4d;
    font-size: 14px;
    font-weight: 600;
    line-height: 1.4;
  `,
  requirementCardDesc: css`
    flex: 1;
    margin-top: 8px;
    overflow: hidden;
    color: #6f6a86;
    font-size: 12px;
    line-height: 1.5;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  `,
  requirementCardMeta: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    color: #8b86a0;
    font-size: 11px;
  `,
  requirementCardActions: css`
    display: flex;
    justify-content: flex-end;
    margin-top: 4px;
  `,
  projectCardMeta: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: 10px;
    color: #8b86a0;
    font-size: 11px;
  `,
  projectCardActions: css`
    display: flex;
    flex-wrap: nowrap;
    align-items: center;
    justify-content: space-between;
    gap: 4px;
    margin-top: 8px;
    padding-top: 8px;
    border-top: 1px solid #f5f2fb;
  `,
  projectCardActionButton: css`
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    padding: 0;
    color: #6f6a86;
    &:hover {
      color: #5b3fd6;
      background: #f4f1ff;
    }
  `,
  appDrawerRoot: css`
    position: absolute !important;
    inset: 0 !important;
    width: 100% !important;
    height: 100% !important;
    .ant-drawer-mask {
      position: absolute !important;
      inset: 0 !important;
    }
    .ant-drawer-content-wrapper {
      box-shadow: -12px 0 40px rgba(38, 28, 84, 0.16) !important;
    }
  `,
  pager: css`
    display: flex;
    justify-content: flex-end;
    margin-top: 4px;
  `,
  boardPager: css`
    display: flex;
    justify-content: center;
    margin-top: 8px;
  `,
  row: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    padding: 14px 16px;
    border: 1px solid #efedf7;
    border-radius: 10px;
    background: #fff;
    cursor: pointer;
    &:hover {
      border-color: #d9d3f2;
    }
  `,
  rowMain: css`
    min-width: 0;
  `,
  rowTitle: css`
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    font-weight: 500;
    color: #241b4d;
  `,
  rowDesc: css`
    margin-top: 4px;
    color: #8b86a0;
    font-size: 12px;
  `,
  rowMeta: css`
    display: flex;
    align-items: center;
    gap: 8px;
    color: #8b86a0;
    font-size: 12px;
    white-space: nowrap;
  `,
  detailHead: css`
    display: flex;
    align-items: center;
    gap: 10px;
  `,
  detailTitle: css`
    font-size: 16px;
    font-weight: 500;
    color: #241b4d;
  `,
  empty: css`
    padding: 40px 0;
  `,
  board: css`
    display: grid;
    grid-template-columns: repeat(6, minmax(210px, 1fr));
    gap: 12px;
    align-items: start;
    overflow-x: auto;
    padding-bottom: 8px;
  `,
  boardColumn: css`
    display: flex;
    flex-direction: column;
    height: 560px;
    padding: 12px;
    border-radius: 12px;
    background: #f7f7f9;
    border: 1px solid transparent;
    overflow: hidden;
    transition: border-color 0.15s ease, background 0.15s ease;
    &:hover {
      border-color: #e6e2f2;
      background: #f4f3f8;
    }
  `,
  laneBody: css`
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding-right: 2px;
  `,
  laneLoading: css`
    display: flex;
    justify-content: center;
    padding: 10px 0;
  `,
  laneHint: css`
    padding: 10px 0;
    text-align: center;
    color: #b3adc4;
    font-size: 11px;
  `,
  gantt: css`
    overflow: auto;
    border: 1px solid #efedf7;
    border-radius: 10px;
    background: #fff;
  `,
  ganttHead: css`
    display: flex;
    position: sticky;
    top: 0;
    z-index: 2;
    background: #faf9ff;
    border-bottom: 1px solid #efedf7;
  `,
  ganttLabelHead: css`
    width: 220px;
    flex-shrink: 0;
    padding: 10px 12px;
    color: #6f6a86;
    font-size: 12px;
    font-weight: 500;
    border-right: 1px solid #efedf7;
  `,
  ganttTimeline: css`
    display: flex;
    position: relative;
    flex-shrink: 0;
    background-image: linear-gradient(
      to right,
      #f3f0fb 1px,
      transparent 1px
    );
  `,
  ganttDay: css`
    display: flex;
    flex-shrink: 0;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 44px;
    border-right: 1px solid #f3f0fb;
    color: #8b86a0;
    font-size: 10px;
  `,
  ganttDayMonth: css`
    color: #b3adc4;
    font-size: 9px;
    line-height: 12px;
  `,
  ganttBody: css`
    min-width: max-content;
  `,
  ganttRow: css`
    display: flex;
    min-height: 54px;
    border-bottom: 1px solid #f7f5fc;
    &:nth-child(even) {
      background: #fcfbff;
    }
  `,
  ganttLabel: css`
    flex-shrink: 0;
    padding: 8px 12px;
    border-right: 1px solid #efedf7;
  `,
  ganttLabelTitle: css`
    overflow: hidden;
    color: #241b4d;
    font-size: 12px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  ganttLabelMeta: css`
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 6px;
    margin-top: 4px;
    overflow: hidden;
    color: #8b86a0;
    font-size: 10px;
    white-space: nowrap;
    & .ant-tag {
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    & > span {
      overflow: hidden;
      text-overflow: ellipsis;
    }
  `,
  ganttBar: css`
    position: absolute;
    top: 50%;
    height: 22px;
    transform: translateY(-50%);
    border-radius: 6px;
    padding: 0 8px;
    overflow: hidden;
    color: #fff;
    cursor: grab;
    font-size: 11px;
    line-height: 22px;
    white-space: nowrap;
    text-overflow: ellipsis;
    touch-action: none;
    user-select: none;
    box-shadow: 0 2px 5px rgba(36, 27, 77, 0.12);
  `,
  ganttBarDragging: css`
    cursor: grabbing;
    opacity: 0.75;
    box-shadow: 0 4px 12px rgba(36, 27, 77, 0.22);
  `,
  ganttBarHandle: css`
    position: absolute;
    top: 0;
    bottom: 0;
    z-index: 1;
    width: 8px;
    cursor: ew-resize;
    &:hover {
      background: rgba(255, 255, 255, 0.45);
    }
  `,
  ganttBarHandleLeft: css`
    left: 0;
    border-radius: 6px 0 0 6px;
  `,
  ganttBarHandleRight: css`
    right: 0;
    border-radius: 0 6px 6px 0;
  `,
  boardColumnTitle: css`
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
    padding: 0 2px;
    font-size: 13px;
    font-weight: 600;
  `,
  boardCount: css`
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 20px;
    height: 20px;
    padding: 0 6px;
    border-radius: 4px;
    background: rgba(36, 27, 77, 0.06);
    color: #6f6a86;
    font-size: 11px;
    font-weight: 500;
  `,
  boardCard: css`
    min-width: 0;
    margin-bottom: 10px;
    padding: 12px;
    border-radius: 10px;
    background: #fff;
    border: 1px solid #eeeaf8;
    box-shadow: 0 1px 2px rgba(36, 27, 77, 0.04);
    cursor: pointer;
    overflow: hidden;
    transition: box-shadow 0.15s ease, transform 0.15s ease;
    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(36, 27, 77, 0.09);
    }
  `,
  boardCardTitle: css`
    color: #241b4d;
    font-size: 13px;
    font-weight: 600;
    line-height: 1.4;
  `,
  boardCardTags: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    min-width: 0;
    margin-top: 8px;
    & .ant-tag {
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  `,
  boardCardMeta: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    color: #8b86a0;
    font-size: 11px;
  `,
  boardCardActions: css`
    display: flex;
    justify-content: flex-end;
    margin-top: 2px;
  `,
  overdue: css`
    color: #ff4d4f;
  `,
  boardEmpty: css`
    padding: 28px 8px;
    border: 1px dashed #ddd8ec;
    border-radius: 8px;
    text-align: center;
    color: #b3adc4;
    font-size: 12px;
  `,
}));
