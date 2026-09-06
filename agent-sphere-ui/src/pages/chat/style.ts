import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  layout: css`
    display: flex;
    flex: 1;
    overflow: hidden;
    border-radius: ${token.borderRadius}px;
    background: ${token.colorBgContainer};
    box-shadow: ${token.boxShadowTertiary};
  `,

  sidebarWrapper: css`
    display: flex;
    position: relative;
  `,

  sidebar: css`
    width: 300px;
    background: ${token.colorBgContainer};
    border-right: 1px solid ${token.colorBorderSecondary};
    display: flex;
    flex-direction: column;
    overflow: hidden;
    transition: width 0.2s ease;
    flex-shrink: 0;
  `,

  sidebarCollapsed: css`
    width: 0;
    border-right: none;
  `,

  sidebarToggle: css`
    position: absolute;
    right: -14px;
    top: 50%;
    transform: translateY(-50%);
    z-index: 10;
    width: 14px;
    height: 48px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: ${token.colorTextTertiary};
    background: ${token.colorBgElevated};
    border: 1px solid ${token.colorBorderSecondary};
    border-left: none;
    border-radius: 0 ${token.borderRadius}px ${token.borderRadius}px 0;
    transition: color 0.2s;

    &:hover {
      color: ${token.colorPrimary};
    }
  `,

  sidebarScroll: css`
    flex: 1;
    overflow-y: auto;
    overscroll-behavior: contain;

    &::-webkit-scrollbar {
      width: 4px;
    }
    &::-webkit-scrollbar-track {
      background: transparent;
    }
    &::-webkit-scrollbar-thumb {
      background: ${token.colorBorderSecondary};
      border-radius: 2px;
    }
    &::-webkit-scrollbar-thumb:hover {
      background: ${token.colorTextTertiary};
    }
  `,

  main: css`
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-width: 0;
    min-height: 0;
    background: ${token.colorBgContainer};
  `,

  messages: css`
    flex: 1;
    overflow-x: hidden;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding: ${token.paddingMD}px;
    display: flex;
    flex-direction: column;
    align-items: center;
    min-height: 0;

    > * {
      width: 100%;
      min-width: 0;
      flex-shrink: 0;
    }
  `,

  footer: css`
    padding: ${token.paddingMD}px;
    border-top: 1px solid ${token.colorBorderSecondary};
    display: flex;
    justify-content: center;
  `,

  footerCenter: css`
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: ${token.paddingLG}px;
    gap: 32px;
    margin-top: -10%;
  `,

  welcomeTitle: css`
    font-size: 32px;
    font-weight: 600;
    color: ${token.colorText};
    text-align: center;
  `,

  header: css`
    padding: ${token.paddingSM}px ${token.paddingMD}px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    display: flex;
    align-items: center;
    gap: ${token.paddingXS}px;
  `,

  statusDot: css`
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: ${token.colorSuccess};
    display: inline-block;
  `,

  sidebarAction: css`
    padding: ${token.paddingSM}px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
  `,

  sidebarNewChat: css`
    padding: ${token.paddingSM}px ${token.paddingSM}px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    font-size: ${token.fontSize}px;
  `,

  sidebarNewChatItem: css`
    color: ${token.colorTextSecondary};
    cursor: pointer;
    transition: color 0.2s;

    &:hover {
      color: ${token.colorPrimary};
    }
  `,

  memorySection: css`
    margin-bottom: ${token.marginMD}px;
  `,

  memoryTextarea: css`
    margin-top: ${token.marginXS}px;
  `,

  memoryButton: css`
    margin-top: ${token.marginSM}px;
  `,

  runItem: css`
    padding: ${token.paddingXS}px 0;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    font-size: ${token.fontSizeSM}px;
  `,

  runTitle: css`
    color: ${token.colorTextSecondary};
    margin-left: ${token.marginSM}px;
  `,

  runPreview: css`
    color: ${token.colorTextTertiary};
    margin-top: ${token.marginXXS}px;
  `,

  runReply: css`
    color: ${token.colorTextSecondary};
    margin-top: ${token.marginXXS}px;
    padding-left: ${token.marginSM}px;
    border-left: 2px solid ${token.colorBorder};
  `,

  emptyText: css`
    color: ${token.colorTextQuaternary};
  `,

  scrollContainer: css`
    max-height: 300px;
    overflow-y: auto;
  `,

  loadMore: css`
    text-align: center;
    margin-bottom: ${token.marginSM}px;
    width: 100%;
    max-width: 940px;
  `,

  sidebarLoadMore: css`
    text-align: center;
    padding: ${token.paddingXS}px ${token.paddingSM}px ${token.paddingSM}px;
  `,

  emptyState: css`
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 24px;
    color: ${token.colorTextQuaternary};
  `,

  emptyIcon: css`
    font-size: 64px;
    color: ${token.colorTextTertiary};
  `,

  markdown: css`
    hr {
      border: none;
      border-top: 1px solid ${token.colorBorderSecondary};
      margin: ${token.marginSM}px 0;
    }
    table {
      border-collapse: collapse;
      width: 100%;
      margin: ${token.marginSM}px 0;
    }
    th, td {
      border: 1px solid ${token.colorBorderSecondary};
      padding: ${token.paddingXS}px ${token.paddingSM}px;
      text-align: left;
    }
    th {
      background: ${token.colorBgLayout};
      font-weight: ${token.fontWeightStrong};
    }
    pre {
      margin: ${token.marginSM}px 0;
      white-space: pre !important;
      overflow-x: auto !important;
      overflow-y: hidden !important;
      overflow-wrap: normal !important;
      word-break: normal !important;
    }
    p > code, li > code {
      background: ${token.colorBgLayout};
      padding: 2px 6px;
      border-radius: ${token.borderRadius}px;
      font-size: 0.9em;
    }
  `,

  landing: css`
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 64px 24px;
    gap: 32px;
  `,

  landingTitle: css`
    font-size: 28px;
    font-weight: 600;
    color: ${token.colorText};
    text-align: center;
    line-height: 1.4;
  `,
  landingRow: css`
    display: flex;
    gap: 12px;
    align-items: center;
    width: 100%;
    max-width: 720px;
  `,

  instanceCardsRow: css`
    display: flex;
    gap: 12px;
    justify-content: center;
    align-items: stretch;
    width: 100%;
    max-width: 720px;
    flex-wrap: wrap;
  `,

  instanceCard: css`
    flex: 1;
    min-width: 180px;
    max-width: 220px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    padding: ${token.paddingMD}px;
    cursor: pointer;
    transition: all 0.2s;
    display: flex;
    flex-direction: column;
    gap: 6px;
    background: ${token.colorBgElevated};

    &:hover {
      border-color: ${token.colorPrimary};
      box-shadow: ${token.boxShadowSecondary};
    }

    &-active, &:active {
      border-color: ${token.colorPrimary};
      background: ${token.colorPrimaryBg};
    }
  `,

  instanceCardName: css`
    font-size: 15px;
    font-weight: 600;
    color: ${token.colorText};
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,

  instanceCardDesc: css`
    font-size: 13px;
    color: ${token.colorTextTertiary};
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    line-height: 1.5;
  `,

  moreLink: css`
    color: ${token.colorPrimary};
    cursor: pointer;
    font-size: 14px;
    display: inline-flex;
    align-items: center;
    white-space: nowrap;
    align-self: center;

    &:hover {
      color: ${token.colorPrimaryHover};
    }
  `,

  emptyInstanceState: css`
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 16px;
    padding: 48px 24px;
  `,

  emptyInstanceIcon: css`
    font-size: 48px;
    color: ${token.colorTextTertiary};
  `,

  emptyInstanceTitle: css`
    font-size: 18px;
    font-weight: 500;
    color: ${token.colorTextSecondary};
    text-align: center;
  `,

  emptyInstanceDesc: css`
    font-size: 14px;
    color: ${token.colorTextTertiary};
    text-align: center;
    line-height: 1.6;
  `,

  emptyTitle: css`
    font-size: ${token.fontSizeLG}px;
    font-weight: 500;
    color: ${token.colorTextSecondary};
    text-align: center;
  `,

  emptyDesc: css`
    font-size: ${token.fontSize}px;
    color: ${token.colorTextTertiary};
    text-align: center;
    max-width: 320px;
    line-height: 1.6;
  `,

  convItem: css`
    display: flex;
    align-items: center;
    width: 100%;
    min-width: 0;

    .conv-label {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .conv-actions {
      display: none;
      flex-shrink: 0;
      margin-left: auto;
      gap: 2px;
    }

    .conv-actions .delete-icon {
      color: ${token.colorError};
    }

    &:hover .conv-actions {
      display: inline-flex;
    }
  `,
}));
