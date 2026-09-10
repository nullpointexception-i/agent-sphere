import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  section: css`
    margin-bottom: ${token.marginLG}px;
  `,
  configItem: css`
    display: flex;
    align-items: center;
    gap: ${token.marginSM}px;
    padding: ${token.paddingSM}px 0;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    &:last-child {
      border-bottom: none;
    }
  `,
  configLabel: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
  `,
  configName: css`
    font-weight: 500;
    color: ${token.colorText};
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  configDesc: css`
    font-size: ${token.fontSizeSM}px;
    color: ${token.colorTextTertiary};
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  configValue: css`
    color: ${token.colorTextSecondary};
    font-family: monospace;
    max-width: 400px;
    min-width: 120px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex-shrink: 0;
  `,
  dangerBtn: css`
    color: ${token.colorError};
    border-color: ${token.colorError};
    &:hover {
      color: ${token.colorErrorHover};
      border-color: ${token.colorErrorHover};
    }
  `,
}));
