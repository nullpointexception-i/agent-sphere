import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  overlay: css`
    position: fixed;
    inset: 24px;
    width: min(1440px, calc(100vw - 48px));
    height: min(900px, calc(100vh - 48px));
    margin: auto;
    z-index: 100;
    border-radius: 16px;
    box-shadow: 0 24px 80px rgba(24, 16, 64, 0.18);
    background: #f5f6f9;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  `,
  header: css`
    height: 64px;
    flex: 0 0 64px;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0 18px;
    background: #fff;
    border-bottom: 1px solid #eef0f4;
  `,
  back: css`
    border: none;
    background: transparent;
    cursor: pointer;
    font-size: 18px;
    color: #52525b;
    padding: 6px;
    border-radius: 8px;
    &:hover {
      background: #f4f4f5;
    }
  `,
  headerText: css`
    display: flex;
    flex-direction: column;
    min-width: 0;
  `,
  headerName: css`
    font-size: 15px;
    font-weight: 600;
    color: #18181b;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  `,
  headerSub: css`
    font-size: 12px;
    color: #a1a1aa;
  `,
  headerActions: css`
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 8px;
  `,
  body: css`
    flex: 1;
    min-height: 0;
    display: flex;
    gap: 16px;
    padding: 16px;
    background: #f5f6f9;
  `,
  chat: css`
    position: relative;
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    background: #fff;
    border: 1px solid #eef0f4;
    border-radius: 12px;
    overflow: hidden;
  `,
  scroll: css`
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 18px 24px 8px;
  `,
  empty: css`
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #a1a1aa;
    font-size: 13px;
  `,
  notice: css`
    margin: 0 18px 8px;
    padding: 8px 12px;
    border-radius: 8px;
    background: #fff7e6;
    color: #ad6800;
    font-size: 12px;
  `,
  composer: css`
    flex: 0 0 auto;
    padding: 12px 18px 16px;
    border-top: 1px solid #f0f0f2;
    display: flex;
    gap: 10px;
    align-items: flex-end;
  `,
  rail: css`
    width: 280px;
    flex: 0 0 280px;
    padding: 18px;
    background: #fff;
    border: 1px solid #eef0f4;
    border-radius: 12px;
    display: flex;
    flex-direction: column;
    gap: 14px;
    overflow-y: auto;
  `,
  railTitle: css`
    font-size: 13px;
    font-weight: 600;
    color: #3f3f46;
  `,
  railHint: css`
    font-size: 12px;
    color: #a1a1aa;
    line-height: 1.6;
  `,
  capRow: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
    border: 1px solid #ececf1;
    border-radius: 9px;
    background: #fff;
    font-size: 13px;
    color: #3f3f46;
  `,
  capCount: css`
    font-variant-numeric: tabular-nums;
    font-weight: 600;
    color: #6d5efc;
  `,
  warning: css`
    padding: 10px 12px;
    border-radius: 9px;
    background: #fff7e6;
    color: #ad6800;
    font-size: 12px;
    line-height: 1.6;
  `,
}));
