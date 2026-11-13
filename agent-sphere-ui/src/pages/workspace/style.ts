import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  backdrop: css`
    min-height: 100vh;
    padding: 24px;
    background:
      radial-gradient(circle at 18% 12%, rgba(124, 108, 255, 0.12), transparent 32%),
      radial-gradient(circle at 82% 82%, rgba(79, 140, 255, 0.1), transparent 30%),
      #f3f2f8;
    display: flex;
    align-items: center;
    justify-content: center;
    box-sizing: border-box;
  `,
  backdropDesktop: css`
    padding: 0;
    background: #f7f6fb;
  `,
  app: css`
    width: min(1180px, 100%);
    height: min(820px, calc(100vh - 48px));
    min-height: 560px;
    display: flex;
    overflow: hidden;
    border-radius: 14px;
    background: #f7f6fb;
    box-shadow:
      0 30px 80px rgba(38, 28, 84, 0.16),
      0 2px 8px rgba(38, 28, 84, 0.06);
  `,
  appDesktop: css`
    width: 100%;
    max-width: none;
    height: 100vh;
    min-height: 0;
    border-radius: 0;
    box-shadow: none;
  `,
  rail: css`
    width: 78px;
    flex: 0 0 78px;
    background: #fbfaff;
    border-right: 1px solid #eeeaf8;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 16px 8px 14px;
    gap: 8px;
  `,
  railLogo: css`
    width: 42px;
    height: 42px;
    border-radius: 11px;
    display: block;
    margin-bottom: 12px;
    box-shadow: 0 8px 18px rgba(26, 26, 26, 0.18);
  `,
  railItem: css`
    width: 58px;
    padding: 9px 0 7px;
    border-radius: 9px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 3px;
    color: #9d98b4;
    cursor: pointer;
    transition: all 0.18s ease;
    user-select: none;
    &:hover {
      color: #6c5ce7;
      background: #f3f0ff;
    }
  `,
  railItemActive: css`
    color: #6c5ce7;
    background: #eeeaff;
    font-weight: 400;
  `,
  railIcon: css`
    font-size: 20px;
    line-height: 1;
  `,
  railLabel: css`
    font-size: 10px;
    line-height: 1.2;
  `,
  railSpacer: css`
    flex: 1;
  `,
  railAvatar: css`
    flex: 0 0 auto;
    cursor: pointer;
  `,
  railUser: css`
    width: 100%;
    margin-top: 8px;
    padding: 2px 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 5px;
    cursor: pointer;
    &:hover {
      .ant-avatar {
        box-shadow: 0 0 0 2px #e2dbff;
      }
    }
  `,
  railUserName: css`
    width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: center;
    color: #6f6a86;
    font-size: 11px;
    line-height: 1.2;
  `,
  main: css`
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    background: #f7f6fb;
    position: relative;
  `,
  avatarDrawerRoot: css`
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
  avatarDrawerBody: css`
    min-height: 100%;
    box-sizing: border-box;
    padding: 0 24px 24px;
    display: flex;
    flex-direction: column;
  `,
  avatarDetailHeader: css`
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 20px 0 18px;
    border-bottom: 1px solid #f0ecfa;
    position: sticky;
    top: 0;
    z-index: 5;
    background: #fff;
  `,
  avatarDetailAvatar: css`
    flex: 0 0 52px;
    border-radius: 15px;
    box-shadow: 0 8px 20px rgba(91, 76, 196, 0.18);
  `,
  avatarDetailHeadText: css`
    flex: 1;
    min-width: 0;
  `,
  avatarDetailName: css`
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: #241b4d;
    font-size: 18px;
    line-height: 1.35;
  `,
  avatarDetailSub: css`
    margin-top: 3px;
    color: #9d98b4;
    font-size: 12px;
  `,
  avatarDetailClose: css`
    flex: 0 0 auto;
    width: 30px;
    height: 30px;
    border: none;
    border-radius: 50%;
    background: #f5f2fb;
    color: #6f6a86;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    &:hover {
      background: #ece7f9;
      color: #241b4d;
    }
  `,
  avatarDetailSection: css`
    padding: 16px 0;
    border-bottom: 1px solid #f4f1fa;
  `,
  avatarDetailLabel: css`
    margin-bottom: 8px;
    color: #9d98b4;
    font-size: 11px;
    letter-spacing: 0.04em;
  `,
  avatarDetailValue: css`
    color: #3a3358;
    font-size: 14px;
    line-height: 1.7;
    white-space: pre-wrap;
    word-break: break-word;
  `,
  avatarRoleTag: css`
    display: inline-flex;
    align-items: center;
    padding: 5px 12px;
    border-radius: 999px;
    background: #f0ebff;
    color: #6c5ce7;
    font-size: 13px;
  `,
  avatarStatusRow: css`
    display: flex;
    align-items: center;
    gap: 8px;
    color: #3a3358;
    font-size: 14px;
  `,
  avatarStatusDot: css`
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #22c55e;
  `,
  avatarStatusDotPaused: css`
    background: #f59e0b;
  `,
  avatarDetailFooter: css`
    position: sticky;
    bottom: 0;
    margin-top: auto;
    padding: 18px 0 2px;
    border-top: 1px solid #f0ecfa;
    background: #fff;
    display: flex;
    flex-direction: column;
    gap: 10px;
  `,
  avatarDetailPrimary: css`
    height: 42px;
    border-radius: 10px;
    font-size: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
  `,
  avatarSecondaryActions: css`
    display: flex;
    gap: 10px;
  `,
  avatarSecondaryButton: css`
    flex: 1;
    height: 38px;
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.16s ease;
  `,
  avatarPauseButton: css`
    border: 1px solid #e4def7;
    background: #f7f5fd;
    color: #6b5bd6;
    &:hover {
      border-color: #d6ccf4;
      background: #f0ebff;
    }
  `,
  avatarEditButton: css`
    border: 1px solid #e8e4f4;
    background: #faf9fd;
    color: #5d5773;
    &:hover {
      border-color: #dcd5ef;
      background: #f4f1fb;
      color: #241b4d;
    }
  `,
  avatarPausedHint: css`
    margin-top: 14px;
    padding: 8px 12px;
    border-radius: 8px;
    background: #fff8e8;
    color: #b7791f;
    font-size: 12px;
    line-height: 1.5;
  `,
  avatarDeleteButton: css`
    border: 1px solid #ffe0e7;
    background: #fff7f9;
    color: #f2637f;
    &:hover {
      border-color: #ffd0da;
      background: #fff0f4;
    }
  `,
  avatarEditForm: css`
    padding-top: 6px;
  `,
  avatarEditFooter: css`
    position: sticky;
    bottom: 0;
    margin-top: auto;
    padding: 18px 0 2px;
    border-top: 1px solid #f0ecfa;
    background: #fff;
    display: flex;
    gap: 10px;
  `,
  avatarCancelButton: css`
    flex: 1;
    height: 42px;
    border: 1px solid #eeeaf7;
    border-radius: 10px;
    background: #f6f4fb;
    color: #5d5773;
    font-size: 14px;
    &:hover {
      border-color: #e2dcf3;
      background: #f0edf9;
      color: #241b4d;
    }
  `,
  avatarSaveButton: css`
    flex: 1;
    height: 42px;
    border-radius: 10px;
    font-size: 14px;
  `,
  topBar: css`
    display: flex;
    align-items: center;
    justify-content: flex-end;
    padding: 18px 26px 10px;
    flex: 0 0 auto;
  `,
  topRight: css`
    display: flex;
    align-items: center;
    gap: 10px;
  `,
  langButton: css`
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 26px;
    padding: 0 10px;
    border: none;
    border-radius: 8px;
    background: #f4f1ff;
    color: #6b5bd6;
    font-size: 12px;
    font-weight: 400;
    cursor: pointer;
    &:hover {
      background: #ece7ff;
    }
  `,
  online: css`
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 4px 10px;
    border-radius: 8px;
    background: #eaf8ef;
    color: #1f9d55;
    font-size: 12px;
  `,
  dot: css`
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #22c55e;
  `,
  content: css`
    flex: 1;
    overflow-y: auto;
    padding: 8px 26px 28px;
  `,
  page: css`
    width: 100%;
    max-width: 980px;
    margin: 0 auto;
  `,
  hero: css`
    margin: 8px 0 22px;
    padding: 28px 28px;
    border-radius: 14px;
    background: linear-gradient(120deg, #efeaff 0%, #e7f0ff 55%, #f3ecff 100%);
    position: relative;
    overflow: hidden;
  `,
  heroTitle: css`
    font-size: 24px;
    line-height: 1.3;
    font-weight: 400;
    color: #231b4d;
    max-width: 62%;
  `,
  heroText: css`
    margin-top: 9px;
    color: #6f6a86;
    font-size: 13px;
    max-width: 58%;
  `,
  heroButton: css`
    margin-top: 18px;
    background: linear-gradient(135deg, #7c6cff, #5b8cff);
    border: none;
    border-radius: 8px;
    font-weight: 400;
    padding: 0 20px;
    box-shadow: 0 8px 18px rgba(108, 92, 231, 0.24);
  `,
  heroBot: css`
    position: absolute;
    right: 34px;
    bottom: 24px;
    width: 118px;
    height: 118px;
    border-radius: 18px;
    background: #fff;
    color: #7c6cff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 50px;
    box-shadow: 0 14px 32px rgba(91, 76, 196, 0.18);
  `,
  heroBotSpark: css`
    position: absolute;
    top: 22px;
    right: 22px;
    color: #ffc53d;
    font-size: 16px;
  `,
  section: css`
    margin-bottom: 22px;
  `,
  sectionHead: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;
  `,
  sectionTitle: css`
    font-size: 14px;
    font-weight: 400;
    color: #241b4d;
  `,
  sectionMore: css`
    font-size: 12px;
    color: #8b86a0;
    cursor: pointer;
  `,
  listCard: css`
    background: #fff;
    border-radius: 7px;
    padding: 6px;
    box-shadow: 0 6px 20px rgba(44, 34, 94, 0.05);
  `,
  row: css`
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: 9px;
    cursor: pointer;
    transition: background 0.15s;
    &:hover {
      background: #faf9ff;
    }
  `,
  avatar: css`
    flex: 0 0 auto;
  `,
  rowBody: css`
    flex: 1;
    min-width: 0;
  `,
  rowTitle: css`
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    font-weight: 400;
    color: #241b4d;
    font-size: 13px;
  `,
  avatarNameRow: css`
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  `,
  avatarName: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 500;
    color: #241b4d;
    font-size: 14px;
  `,
  avatarTags: css`
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 4px;
    .ant-tag {
      margin-inline-end: 0;
    }
  `,
  avatarForm: css`
    display: grid;
    grid-template-columns: 64px minmax(0, 1fr);
    align-items: center;
    column-gap: 12px;
    row-gap: 10px;
    padding: 12px;
    border-radius: 10px;
    background: #faf9fe;
    > .ant-tag,
    > .ant-switch {
      justify-self: start;
    }
  `,
  runtimeOption: css`
    margin-inline-end: 0;
    width: 100%;
    padding: 8px 10px;
    border: 1px solid #eceaf5;
    border-radius: 8px;
    transition: border-color 0.15s ease, background 0.15s ease;
    &:hover {
      border-color: #d9d3f5;
    }
    &:has(.ant-radio-checked) {
      border-color: #8b7bff;
      background: #f7f6ff;
    }
  `,
  runtimeDot: css`
    width: 8px;
    height: 8px;
    border-radius: 50%;
    display: inline-block;
  `,
  avatarFormLabel: css`
    color: #8b86a0;
    font-size: 12px;
    white-space: nowrap;
  `,
  conversationNameText: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  rowDesc: css`
    margin-top: 3px;
    color: #8b86a0;
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  rowTime: css`
    margin-left: auto;
    flex: 0 0 auto;
    color: #a7a2b8;
    font-size: 11px;
    font-weight: 400;
    white-space: nowrap;
  `,
  unread: css`
    min-width: 18px;
    height: 18px;
    padding: 0 5px;
    border-radius: 8px;
    background: #7c6cff;
    color: #fff;
    font-size: 10px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  `,
  statusText: css`
    font-size: 11px;
    color: #1f9d55;
    font-weight: 400;
  `,
  statusPaused: css`
    font-size: 11px;
    color: #a7a2b8;
    font-weight: 400;
  `,
  pageHead: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 18px;
  `,
  pageTitle: css`
    font-size: 18px;
    font-weight: 400;
    color: #241b4d;
  `,
  pageSubtitle: css`
    margin-top: 4px;
    color: #8b86a0;
    font-size: 12px;
  `,
  emptyBlock: css`
    grid-column: 1 / -1;
    padding: 24px 0;
  `,
  primaryAction: css`
    height: 34px;
    padding: 0 16px;
    border: none !important;
    border-radius: 8px !important;
    background: linear-gradient(135deg, #7c6cff, #5b8cff) !important;
    color: #fff !important;
    font-size: 13px;
    font-weight: 400;
    box-shadow: 0 8px 18px rgba(108, 92, 231, 0.22);
    &:hover {
      background: linear-gradient(135deg, #6c5ce7, #4f8cff) !important;
      color: #fff !important;
    }
  `,
  detailDesc: css`
    padding: 12px 14px;
    border-radius: 8px;
    background: #faf8ff;
    color: #5b5473;
    font-size: 13px;
    line-height: 1.6;
    margin-bottom: 12px;
    white-space: pre-wrap;
  `,
  editMetaRow: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 0 2px;
  `,
  editStatus: css`
    color: #a7a2b8;
    font-size: 11px;
  `,
  editSecondaryActions: css`
    display: flex;
    align-items: center;
    gap: 6px;
  `,
  textAction: css`
    height: 24px;
    padding: 0;
    border: none;
    background: transparent;
    color: #6f6a86;
    font-size: 12px;
    cursor: pointer;
    &:hover {
      color: #6c5ce7;
    }
  `,
  dangerTextAction: css`
    height: 24px;
    padding: 0;
    border: none;
    background: transparent;
    color: #ff6b8a;
    font-size: 12px;
    cursor: pointer;
    &:hover {
      color: #ff4d73;
    }
  `,
  actionDivider: css`
    color: #d9d3e8;
    font-size: 12px;
  `,
  avatarGrid: css`
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 14px;
  `,
  avatarCard: css`
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    box-shadow: 0 6px 20px rgba(44, 34, 94, 0.05);
    display: flex;
    gap: 12px;
    align-items: flex-start;
    cursor: pointer;
    transition: box-shadow 0.18s ease, transform 0.18s ease;
    &:hover {
      box-shadow: 0 12px 28px rgba(44, 34, 94, 0.1);
      transform: translateY(-1px);
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
  /** Title line of the ephemeral row: single line, never re-wraps the header. */
  /** Reasoning chip in the title line: scroll-in tail, floating expand panel. */
  /** Typing-dots placeholder bubble shown while reasoning runs. */
  meHeader: css`
    display: flex;
    align-items: center;
    gap: 14px;
    background: #fff;
    border-radius: 8px;
    padding: 20px;
    margin-bottom: 18px;
    box-shadow: 0 6px 20px rgba(44, 34, 94, 0.05);
  `,
  meName: css`
    font-size: 17px;
    font-weight: 400;
    color: #241b4d;
  `,
  meBio: css`
    margin-top: 4px;
    color: #8b86a0;
    font-size: 12px;
  `,
  menuCard: css`
    background: #fff;
    border-radius: 7px;
    overflow: hidden;
    box-shadow: 0 6px 20px rgba(44, 34, 94, 0.05);
  `,
  menuRow: css`
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 15px 18px;
    border-bottom: 1px solid #f4f2fb;
    cursor: pointer;
    &:last-child {
      border-bottom: none;
    }
    &:hover {
      background: #faf9ff;
    }
  `,
  menuIcon: css`
    width: 36px;
    height: 36px;
    border-radius: 8px;
    background: #f0edff;
    color: #6c5ce7;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 18px;
  `,
  menuBody: css`
    flex: 1;
    min-width: 0;
  `,
  menuTitle: css`
    font-weight: 400;
    color: #241b4d;
    font-size: 13px;
  `,
  menuDesc: css`
    margin-top: 2px;
    color: #a7a2b8;
    font-size: 11px;
  `,
  logoutRow: css`
    display: flex;
    justify-content: center;
    padding: 18px 0 8px;
  `,
  logoutButton: css`
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 34px;
    padding: 0 16px;
    border: 1px solid #ffe0e7;
    border-radius: 8px;
    background: #fff5f7;
    color: #ff6b8a;
    font-size: 12px;
    cursor: pointer;
    &:hover {
      border-color: #ffc2ce;
      background: #ffeef2;
      color: #ff4d73;
    }
  `,
  providerRow: css`
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 14px 18px;
    border-bottom: 1px solid #f4f2fb;
    &:last-child {
      border-bottom: none;
    }
  `,
  providerName: css`
    flex: 1;
    font-weight: 400;
    color: #241b4d;
    font-size: 13px;
  `,
  tagOk: css`
    color: #1f9d55;
    background: #eaf8ef;
    border-radius: 8px;
    padding: 3px 10px;
    font-size: 11px;
    white-space: nowrap;
  `,
  tagMuted: css`
    color: #a7a2b8;
    background: #f4f2fb;
    border-radius: 8px;
    padding: 3px 10px;
    font-size: 11px;
    white-space: nowrap;
  `,
  contactsGrid: css`
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;
    align-items: stretch;
    min-height: 0;
    @media (max-width: 900px) {
      grid-template-columns: 1fr;
    }
  `,
  contactsPanel: css`
    background: #fff;
    border-radius: 7px;
    padding: 10px;
    box-shadow: 0 6px 20px rgba(44, 34, 94, 0.05);
    display: flex;
    flex-direction: column;
    height: calc(100vh - 330px);
    min-height: 320px;
    max-height: 560px;
  `,
  contactsPanelBody: css`
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding-right: 2px;
  `,
  contactsPanelHead: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 8px 10px;
    font-weight: 400;
    color: #241b4d;
    font-size: 13px;
  `,
  settingDrawer: css`
    .ant-drawer-header {
      border-bottom: 1px solid #f4f2fb;
    }
    .ant-drawer-title {
      font-weight: 400;
      color: #241b4d;
    }
    .ant-drawer-body {
      padding: 18px 20px 28px;
    }
  `,
  softModal: css`
    .ant-modal-content {
      border-radius: 14px;
      padding: 24px;
    }
    .ant-modal-header {
      border-radius: 14px 14px 0 0;
    }
    .ant-modal-title {
      font-weight: 400;
      font-size: 15px;
    }
    .ant-btn {
      border-radius: 8px;
    }
  `,
  createModal: css`
    .ant-modal-content {
      border-radius: 12px;
      padding: 0;
      overflow: hidden;
      box-shadow: 0 30px 80px rgba(38, 28, 84, 0.2);
    }
    .ant-modal-body {
      padding: 0;
    }
  `,
  createHero: css`
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    margin: 4px 4px 0;
    padding: 10px 44px 10px 8px;
    border-radius: 10px;
    background: linear-gradient(135deg, #f0ebff 0%, #e8f2ff 100%);
  `,
  createHeroText: css`
    flex: 1;
    min-width: 0;
  `,
  createOrb: css`
    width: 56px;
    height: 56px;
    flex: 0 0 56px;
    border-radius: 16px;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
    background: linear-gradient(135deg, #8a7bff, #5b8cff);
    color: #fff;
    font-size: 24px;
    font-weight: 400;
    box-shadow: 0 10px 22px rgba(91, 76, 196, 0.22);
  `,
  createOrbInitial: css`
    font-size: 22px;
    font-weight: 400;
    line-height: 1;
  `,
  createOrbUser: css`
    font-size: 24px;
  `,
  createOrbSpark: css`
    position: absolute;
    top: 8px;
    right: 8px;
    color: #ffe58f;
    font-size: 10px;
  `,
  createTitle: css`
    font-size: 18px;
    font-weight: 400;
    color: #241b4d;
  `,
  createSubtitle: css`
    margin-top: 4px;
    font-size: 12px;
    color: #6f6a86;
  `,
  createClose: css`
    position: absolute;
    top: 6px;
    right: 6px;
    width: 30px;
    height: 30px;
    border: none;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.72);
    color: #6f6a86;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    &:hover {
      background: #fff;
      color: #241b4d;
    }
  `,
  createBody: css`
    padding: 8px 4px 0;
  `,
  fieldBlock: css`
    margin-bottom: 10px;
  `,
  fieldLabel: css`
    font-size: 13px;
    font-weight: 400;
    color: #3a3358;
    margin-bottom: 8px;
  `,
  required: css`
    color: #ff6b8a;
  `,
  optional: css`
    margin-left: 4px;
    color: #a7a2b8;
    font-size: 11px;
    font-weight: 400;
  `,
  createNote: css`
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 12px;
    border-radius: 8px;
    background: #f7f5ff;
    color: #8b86a0;
    font-size: 11px;
    margin-bottom: 2px;
  `,
  fieldHint: css`
    margin-top: 6px;
    font-size: 11px;
    color: #a7a2b8;
  `,
  softField: css`
    background: #fff;
    border: 1px solid #e6e1f2;
    border-radius: 8px;
    padding: 11px 14px;
    font-size: 13px;
    box-shadow: none;
    &:focus,
    &:focus-within {
      border-color: #b9adff;
      background: #fff;
      box-shadow: 0 0 0 3px rgba(124, 108, 255, 0.1);
    }
  `,
  softTextarea: css`
    background: #fff;
    border: 1px solid #e6e1f2;
    border-radius: 8px;
    padding: 12px 14px;
    font-size: 13px;
    box-shadow: none;
    &:focus,
    &:focus-within {
      border-color: #b9adff;
      background: #fff;
      box-shadow: 0 0 0 3px rgba(124, 108, 255, 0.1);
    }
  `,
  createFooter: css`
    display: flex;
    justify-content: center;
    gap: 12px;
    padding: 12px 4px 4px;
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
