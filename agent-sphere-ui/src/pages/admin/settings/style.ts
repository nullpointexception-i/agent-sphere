import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  section: css`
    margin-bottom: ${token.marginLG}px;
  `,
  configItem: css`
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: ${token.paddingSM}px 0;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    &:last-child {
      border-bottom: none;
    }
  `,
  configLabel: css`
    flex: 1;
    overflow: hidden;
  `,
  configName: css`
    font-weight: 500;
    color: ${token.colorText};
  `,
  configDesc: css`
    font-size: ${token.fontSizeSM}px;
    color: ${token.colorTextTertiary};
  `,
  configValue: css`
    color: ${token.colorTextSecondary};
    font-family: monospace;
    max-width: 300px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
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
