import { createStyles } from 'antd-style';

export const useChatStyles = createStyles(({ css }) => ({
  chatHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 14px 22px;
    background: #f7f6fb;
    border-bottom: 1px solid #efedf7;
    flex: 0 0 auto;
  `,
  chatHeaderMain: css`
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  `,
  headerIconButton: css`
    width: 30px;
    height: 30px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #7c7790;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    font-size: 16px;
    &:hover {
      background: #eeeaff;
      color: #6c5ce7;
    }
  `,
  pinnedBar: css`
    flex: 0 0 auto;
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 8px 18px 0;
    padding: 9px 12px;
    border-radius: 8px;
    background: #fff;
    color: #6f6a86;
    font-size: 12px;
    box-shadow: 0 3px 10px rgba(44, 34, 94, 0.04);
  `,
  pinnedText: css`
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  pinnedClose: css`
    width: 22px;
    height: 22px;
    flex: 0 0 22px;
    border: none;
    border-radius: 50%;
    background: transparent;
    color: #a7a2b8;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    &:hover {
      background: #f4f2fb;
      color: #6c5ce7;
    }
  `,
  chatTitle: css`
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 400;
    color: #241b4d;
    font-size: 14px;
  `,
  chatTitleRow: css`
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  `,
  chatTitleWrap: css`
    flex: 1;
    min-width: 0;
  `,
  agentTag: css`
    flex: 0 0 auto;
    padding: 1px 6px;
    border-radius: 6px;
    background: #e8f2ff;
    color: #3b7ddd;
    font-size: 10px;
    font-weight: 400;
    line-height: 1.5;
  `,
  chatMembers: css`
    font-size: 11px;
    color: #a7a2b8;
    margin-top: 2px;
  `,
  messages: css`
    flex: 1;
    overflow-y: auto;
    padding: 18px 22px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  `,
  messageRow: css`
    display: flex;
    gap: 8px;
    align-items: flex-start;
  `,
  messageRowSelf: css`
    flex-direction: row-reverse;
  `,
  bubbleWrap: css`
    max-width: 68%;
  `,
  bubbleWrapSelf: css`
    text-align: right;
  `,
  messageMeta: css`
    font-size: 10px;
    color: #a7a2b8;
    margin-bottom: 3px;
  `,
  messageMetaSelf: css`
    text-align: right;
  `,
  bubble: css`
    padding: 10px 13px;
    border-radius: 9px;
    background: #fff;
    color: #3a3358;
    font-size: 13px;
    line-height: 1.6;
    word-break: break-word;
    text-align: left;
    box-shadow: 0 3px 10px rgba(44, 34, 94, 0.04);
  `,
  bubbleSelf: css`
    background: linear-gradient(135deg, #95ecb0, #7fdc9d);
    color: #143d27;
    text-align: left;
  `,
  messageSending: css`
    display: block;
    margin-top: 4px;
    text-align: right;
    font-size: 10px;
    color: #a7a2b8;
  `,
  messageRetry: css`
    display: block;
    margin-top: 4px;
    margin-left: auto;
    padding: 0;
    border: none;
    background: transparent;
    text-align: right;
    font-size: 10px;
    color: #f2637f;
    cursor: pointer;
    &:hover {
      text-decoration: underline;
    }
  `,
  taskCard: css`
    padding: 12px 14px;
    border-radius: 10px;
    background: #fff;
    border: 1px solid #eeeaf8;
    box-shadow: 0 3px 12px rgba(44, 34, 94, 0.05);
  `,
  taskCardTop: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
  `,
  taskCardTitle: css`
    flex: 1;
    min-width: 0;
    color: #241b4d;
    font-size: 13px;
    line-height: 1.5;
    word-break: break-word;
  `,
  taskCardStatus: css`
    flex: 0 0 auto;
    padding: 1px 8px;
    border-radius: 999px;
    font-size: 11px;
    line-height: 1.6;
  `,
  taskCardStatusRunning: css`
    background: #eef4ff;
    color: #3b7ddd;
  `,
  taskCardStatusDone: css`
    background: #eaf8ef;
    color: #1f9d55;
  `,
  taskCardStatusFailed: css`
    background: #fff0f4;
    color: #f2637f;
  `,
  taskCardSummary: css`
    margin-top: 8px;
    color: #6f6a86;
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  `,
  agentInputPrompt: css`
    margin-top: 8px;
    color: #241b4d;
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  `,
  agentInputOptions: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 8px;
  `,
  agentInputOption: css`
    padding: 4px 12px;
    border-radius: 999px;
    border: 1px solid #ddd6f3;
    background: #fff;
    color: #241b4d;
    font-size: 12px;
    cursor: pointer;
  `,
  agentInputOptionActive: css`
    background: #6e5bf0;
    border-color: #6e5bf0;
    color: #fff;
  `,
  agentInputOptionDisabled: css`
    opacity: 0.55;
    cursor: default;
  `,
  agentInputToolCall: css`
    margin-top: 8px;
    padding: 8px 10px;
    border-radius: 8px;
    background: #f7f5fd;
    border: 1px solid #eeeaf8;
    font-size: 12px;
    color: #241b4d;
  `,
  agentInputToolName: css`
    font-weight: 600;
    word-break: break-word;
  `,
  agentInputToolArgs: css`
    margin-top: 4px;
    color: #6f6a86;
    font-size: 11px;
    word-break: break-all;
    max-height: 72px;
    overflow: hidden;
  `,
  agentInputDecisions: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 6px;
  `,
  agentInputDecision: css`
    padding: 2px 10px;
    border-radius: 999px;
    border: 1px solid #ddd6f3;
    background: #fff;
    color: #5c5578;
    font-size: 11px;
    cursor: pointer;
  `,
  agentInputDecisionActive: css`
    background: #3b7ddd;
    border-color: #3b7ddd;
    color: #fff;
  `,
  agentInputDecisionDeny: css`
    border-color: #f2637f;
    color: #f2637f;
  `,
  agentInputSubmit: css`
    margin-top: 10px;
    width: fit-content;
    padding: 5px 16px;
    border-radius: 8px;
    border: none;
    background: #6e5bf0;
    color: #fff;
    font-size: 12px;
    cursor: pointer;
  `,
  agentInputSubmitDisabled: css`
    opacity: 0.5;
    cursor: default;
  `,
  agentInputDoneHint: css`
    margin-top: 8px;
    color: #9b95b5;
    font-size: 12px;
  `,
  agentInputDenied: css`
    margin-top: 8px;
    color: #d46b08;
    font-size: 12px;
  `,
  agentStreamRow: css`
    display: flex;
    gap: 8px;
    align-items: flex-start;
  `,
  agentStreamBubbleWrap: css`
    max-width: 68%;
  `,
  agentStreamBubble: css`
    padding: 10px 13px;
    border-radius: 9px;
    background: #fff;
    border: 1px dashed #e4defc;
    color: #3a3358;
    font-size: 13px;
    line-height: 1.6;
    word-break: break-word;
    text-align: left;
    box-shadow: 0 3px 10px rgba(44, 34, 94, 0.04);
  `,
  agentStreamMeta: css`
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 100%;
  `,
  agentStreamChipWrap: css`
    position: relative;
    display: inline-flex;
    margin-left: 8px;
    vertical-align: middle;
  `,
  agentStreamChip: css`
    display: inline-flex;
    align-items: center;
    max-width: 180px;
    padding: 1px 8px;
    border: 1px solid #e4defc;
    border-radius: 10px;
    background: #f7f5fd;
    font-size: 11px;
    color: #8b86a0;
    line-height: 1.6;
    cursor: pointer;
    user-select: none;
    &:hover {
      border-color: #cbbef7;
      color: #6f6594;
    }
  `,
  agentStreamChipText: css`
    display: block;
    overflow: hidden;
    white-space: nowrap;
    text-align: left;
    scrollbar-width: none;
    &::-webkit-scrollbar {
      display: none;
    }
  `,
  agentStreamChipPanel: css`
    position: absolute;
    top: calc(100% + 6px);
    left: 0;
    z-index: 30;
    width: 320px;
    max-height: 260px;
    overflow-y: auto;
    padding: 10px 12px;
    border: 1px solid #eeeaf8;
    border-radius: 10px;
    background: #fff;
    box-shadow: 0 10px 30px rgba(44, 34, 94, 0.14);
  `,
  agentStreamChipPanelLive: css`
    border-color: #e4defc;
    background: #fbfaff;
  `,
  agentStreamChipPanelText: css`
    margin: 0;
    font-family: inherit;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 12px;
    color: #6f6a86;
    line-height: 1.7;
  `,
  agentStreamDots: css`
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 16px;
    span {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: #8f7ff7;
      @keyframes agent-dot-bounce {
        0%,
        60%,
        100% {
          transform: translateY(0);
          opacity: 0.4;
        }
        30% {
          transform: translateY(-4px);
          opacity: 1;
        }
      }
      animation: agent-dot-bounce 1.2s ease-in-out infinite;
    }
    span:nth-child(2) {
      animation-delay: 0.15s;
    }
    span:nth-child(3) {
      animation-delay: 0.3s;
    }
  `,
  agentStreamReplyBubble: css`
    padding: 10px 13px;
    border-radius: 9px;
    background: #fff;
    color: #3a3358;
    font-size: 13px;
    line-height: 1.6;
    word-break: break-word;
    text-align: left;
    box-shadow: 0 3px 10px rgba(44, 34, 94, 0.04);
    transform-origin: 0 100%;
    @keyframes agent-reply-enter {
      from {
        opacity: 0;
        transform: translateY(6px);
      }
      to {
        opacity: 1;
        transform: translateY(0);
      }
    }
    animation: agent-reply-enter 180ms ease-out;
  `,
  messageMarkdown: css`
    font-size: 13px;
    line-height: 1.6;
    color: inherit;
    word-break: break-word;
    p {
      margin: 0 0 8px;
    }
    p:last-child {
      margin-bottom: 0;
    }
    h1,
    h2,
    h3,
    h4,
    h5,
    h6 {
      margin: 10px 0 4px;
      font-weight: 600;
      line-height: 1.4;
      word-break: break-word;
    }
    ul,
    ol {
      margin: 4px 0 8px;
      padding-left: 20px;
    }
    hr {
      border: none;
      border-top: 1px solid #eeeaf8;
      margin: 8px 0;
    }
    table {
      border-collapse: collapse;
      width: 100%;
      margin: 8px 0;
      display: block;
      overflow-x: auto;
    }
    th,
    td {
      border: 1px solid #eeeaf8;
      padding: 5px 9px;
      text-align: left;
      font-size: 12px;
      word-break: break-word;
    }
    th {
      background: #f7f5fd;
      font-weight: 600;
    }
    pre {
      margin: 8px 0;
      padding: 8px 10px;
      border-radius: 8px;
      background: #f7f5fd;
      overflow-x: auto;
      overflow-wrap: normal;
      word-break: normal;
      white-space: pre !important;
    }
    pre code {
      background: transparent;
      padding: 0;
      font-size: 12px;
    }
    p > code,
    li > code {
      background: #f7f5fd;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 0.9em;
    }
    blockquote {
      margin: 8px 0;
      padding: 2px 0 2px 10px;
      border-left: 3px solid #ddd6f3;
      color: #6f6a86;
    }
    a {
      color: #6e5bf0;
      word-break: break-all;
    }
    img {
      max-width: 100%;
    }
  `,
  messageMarkdownStreaming: css`
    > :last-child::after {
      content: '';
      display: inline-block;
      width: 6px;
      height: 13px;
      margin-left: 2px;
      vertical-align: -2px;
      background: #7c6cff;
      @keyframes agent-markdown-blink {
        50% {
          opacity: 0;
        }
      }
      animation: agent-markdown-blink 1s steps(1) infinite;
    }
  `,
  agentStreamStatus: css`
    margin-top: 6px;
    font-size: 12px;
    color: #8b86a0;
  `,
  agentStreamError: css`
    margin-top: 6px;
    font-size: 12px;
    color: #f2637f;
  `,
  agentPausedBanner: css`
    flex: 0 0 auto;
    margin: 0 18px;
    padding: 8px 12px;
    border-radius: 8px;
    background: #fff8e8;
    color: #b7791f;
    font-size: 12px;
    line-height: 1.5;
  `,
  composer: css`
    display: flex;
    align-items: flex-end;
    gap: 10px;
    padding: 12px 18px 16px;
    background: transparent;
    flex: 0 0 auto;
  `,
  composerInput: css`
    flex: 1;
    resize: none;
    border: 1px solid #ece8f6;
    outline: none;
    background: #fff;
    border-radius: 9px;
    padding: 12px 14px;
    font-size: 13px;
    min-height: 46px;
    max-height: 120px;
    box-shadow: 0 4px 14px rgba(44, 34, 94, 0.04);
    &:focus {
      border-color: #cfc6ff;
    }
  `,
  composerSend: css`
    border: none;
    background: #7c6cff;
    color: #fff;
    border-radius: 9px;
    height: 46px;
    min-width: 58px;
    padding: 0 16px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    font-size: 13px;
    transition: background 0.15s ease, opacity 0.15s ease;
    &:hover {
      background: #6c5ce7;
    }
  `,
  composerSendDisabled: css`
    opacity: 0.45;
    cursor: not-allowed;
    &:hover {
      background: #7c6cff;
    }
  `,
  settingsScrim: css`
    position: absolute;
    inset: 0;
    z-index: 10;
    background: rgba(30, 25, 50, 0.12);
  `,
  settingsPanel: css`
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    z-index: 11;
    width: 380px;
    display: flex;
    flex-direction: column;
    background: #fff;
    border-left: 1px solid #efedf7;
    box-shadow: -16px 0 36px rgba(38, 28, 84, 0.14);
    animation: settingsSlideIn 0.18s ease-out;
    @keyframes settingsSlideIn {
      from {
        transform: translateX(16px);
        opacity: 0.6;
      }
      to {
        transform: translateX(0);
        opacity: 1;
      }
    }
  `,
  settingsPanelHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 20px;
    border-bottom: 1px solid #f4f2fb;
    color: #241b4d;
    font-size: 14px;
    font-weight: 400;
    flex: 0 0 auto;
  `,
  settingsClose: css`
    width: 28px;
    height: 28px;
    border: none;
    border-radius: 50%;
    background: transparent;
    color: #8b86a0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    &:hover {
      background: #f4f2fb;
      color: #241b4d;
    }
  `,
  settingsPanelBody: css`
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 18px 20px 28px;
  `,
  settingSection: css`
    padding-bottom: 18px;
    margin-bottom: 18px;
    border-bottom: 1px solid #f4f2fb;
    &:last-child {
      border-bottom: none;
    }
  `,
  settingLabel: css`
    margin-bottom: 8px;
    font-size: 12px;
    font-weight: 400;
    color: #3a3358;
  `,
  settingHint: css`
    margin-top: 6px;
    color: #a7a2b8;
    font-size: 11px;
    line-height: 1.5;
  `,
  settingActions: css`
    display: flex;
    justify-content: flex-end;
    margin-top: 10px;
  `,
  inlineField: css`
    display: flex;
    align-items: center;
    gap: 8px;
  `,
  memberList: css`
    margin-top: 10px;
    max-height: 300px;
    overflow-y: auto;
  `,
  memberGrid: css`
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px 4px;
    padding: 2px;
  `,
  memberCard: css`
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 8px 2px 6px;
    border-radius: 12px;
    &:hover {
      background: #faf9ff;
    }
    &:hover .member-card-more {
      opacity: 1;
    }
  `,
  memberCardMore: css`
    position: absolute;
    top: 4px;
    right: 6px;
    color: #a7a2b8;
    font-size: 12px;
    opacity: 0.45;
    transition: opacity 0.15s ease;
  `,
  memberCardName: css`
    width: 100%;
    color: #3a3358;
    font-size: 12px;
    text-align: center;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  memberCardRole: css`
    color: #a7a2b8;
    font-size: 10px;
    line-height: 14px;
  `,
  memberActionCard: css`
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 8px 2px 6px;
    border: none;
    background: none;
    border-radius: 12px;
    cursor: pointer;
    &:hover .member-action-box {
      border-color: #b9b3d6;
      color: #8d87a8;
    }
  `,
  memberActionBox: css`
    width: 56px;
    height: 56px;
    border-radius: 14px;
    border: 1.5px dashed #d9d5ea;
    color: #b9b3d6;
    font-size: 22px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #fff;
    transition: all 0.15s ease;
  `,
  settingSwitchRow: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 14px 0;
    border-bottom: 1px solid #f4f2fb;
    &:last-child {
      border-bottom: none;
    }
  `,
  chatListTitle: css`
    font-weight: 400;
    font-size: 13px;
    color: #3a3358;
  `,
  chatLayout: css`
    flex: 1;
    display: flex;
    min-height: 0;
  `,
  conversationPanel: css`
    width: 310px;
    flex: 0 0 310px;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: #fff;
    border-right: 1px solid #efedf7;
  `,
  conversationHeader: css`
    padding: 14px 16px 12px;
    border-bottom: 1px solid #f4f2fb;
  `,
  searchRow: css`
    display: flex;
    align-items: center;
    gap: 10px;
  `,
  addCircle: css`
    width: 32px;
    height: 32px;
    flex: 0 0 32px;
    border-radius: 50%;
    border: 1px solid #e6e1f2;
    background: #fff;
    color: #6c5ce7;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    font-size: 14px;
    transition: all 0.15s ease;
    &:hover {
      border-color: #cfc6ff;
      background: #f6f3ff;
    }
  `,
  conversationScroll: css`
    flex: 1;
    overflow-y: auto;
    padding: 6px;
  `,
  conversationRow: css`
    display: flex;
    gap: 10px;
    padding: 10px;
    border-radius: 8px;
    cursor: pointer;
    align-items: center;
    &:hover {
      background: #f8f6ff;
    }
  `,
  conversationRowActive: css`
    background: #eef9f2;
  `,
  conversationRowPinned: css`
    background: #f5f3fc;
    &:hover {
      background: #efecfa;
    }
  `,
  chatPanel: css`
    position: relative;
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: #f7f6fb;
  `,
  chatEmpty: css`
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #a7a2b8;
    font-size: 13px;
  `,
  loadMoreHint: css`
    padding: 10px 0;
    text-align: center;
    color: #a7a2b8;
    font-size: 11px;
  `,
  groupModal: css`
    .ant-modal-content {
      border-radius: 10px;
      padding: 0;
      overflow: hidden;
      box-shadow: 0 30px 80px rgba(38, 28, 84, 0.2);
    }
    .ant-modal-body {
      padding: 0;
    }
  `,
  groupModalBody: css`
    display: flex;
    height: 600px;
    background: #fff;
  `,
  groupLeft: css`
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    border-right: 1px solid #efedf7;
    background: #f7f7f8;
  `,
  groupSearch: css`
    padding: 14px 16px;
    background: #fff;
  `,
  groupSpin: css`
    flex: 1;
    min-height: 0;
    display: flex;
    .ant-spin-container {
      flex: 1;
      min-height: 0;
      display: flex;
    }
  `,
  groupList: css`
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 4px 0 12px;
  `,
  groupSectionLabel: css`
    padding: 10px 20px 4px;
    color: #a7a2b8;
    font-size: 12px;
  `,
  groupContactRow: css`
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 20px;
    cursor: pointer;
    &:hover {
      background: #efeff1;
    }
  `,
  groupContactRowActive: css`
    background: #e9e8ec;
  `,
  groupCheck: css`
    width: 20px;
    height: 20px;
    flex: 0 0 20px;
    border-radius: 50%;
    border: 1px solid #d4d1dd;
    background: #fff;
    color: #fff;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
  `,
  groupCheckActive: css`
    border-color: #7c6cff;
    background: #7c6cff;
  `,
  groupContactName: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: #3a3358;
    font-size: 13px;
  `,
  groupRight: css`
    width: 320px;
    flex: 0 0 320px;
    display: flex;
    flex-direction: column;
    padding: 18px 20px 16px;
  `,
  groupRightTitle: css`
    color: #3a3358;
    font-size: 14px;
    font-weight: 400;
  `,
  groupSelectedList: css`
    flex: 1;
    overflow-y: auto;
    margin-top: 14px;
  `,
  groupRightEmpty: css`
    padding-top: 40px;
    text-align: center;
    color: #b6b1c4;
    font-size: 12px;
  `,
  groupSelectedRow: css`
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 0;
  `,
  groupSelectedName: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: #3a3358;
    font-size: 13px;
  `,
  groupRemove: css`
    width: 22px;
    height: 22px;
    border: none;
    border-radius: 50%;
    background: transparent;
    color: #b6b1c4;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    &:hover {
      background: #f4f2fb;
      color: #6c5ce7;
    }
  `,
  groupFooter: css`
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    padding-top: 14px;
    border-top: 1px solid #f4f2fb;
  `,
  groupConfirm: css`
    min-width: 88px;
    height: 34px;
    border: none;
    border-radius: 8px;
    background: #dcd9e6;
    color: #fff;
    font-size: 13px;
    &:not(:disabled) {
      background: #7c6cff;
      color: #fff;
    }
    &:hover:not(:disabled) {
      background: #6c5ce7 !important;
      color: #fff !important;
    }
  `,
  groupCancel: css`
    min-width: 88px;
    height: 34px;
    border: 1px solid #ece9f4;
    border-radius: 8px;
    background: #fff;
    color: #6f6a86;
    font-size: 13px;
    &:hover {
      border-color: #cfc6ff !important;
      color: #6c5ce7 !important;
    }
  `,
  selfTag: css`
    flex: 0 0 auto;
    padding: 1px 6px;
    border-radius: 6px;
    background: #ede9ff;
    color: #6c5ce7;
    font-size: 10px;
    font-weight: 400;
    line-height: 1.5;
  `,
  ghostPill: css`
    border-radius: 8px;
    border: 1px solid #e6e1f2;
    background: #fff;
    color: #6f6a86;
    padding: 0 14px;
    height: 32px;
    font-size: 12px;
  `,
  primaryPill: css`
    border-radius: 8px;
    border: none;
    background: linear-gradient(135deg, #7c6cff, #5b8cff);
    color: #fff;
    padding: 0 16px;
    height: 32px;
    font-size: 12px;
    font-weight: 400;
    box-shadow: 0 8px 18px rgba(108, 92, 231, 0.24);
    &:hover {
      background: linear-gradient(135deg, #6c5ce7, #4f8cff) !important;
      color: #fff !important;
    }
  `,
}));
