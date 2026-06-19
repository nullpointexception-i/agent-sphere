import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  cardGrid: css`
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
    gap: ${token.paddingMD}px;
    padding: ${token.paddingMD}px;
  `,

  card: css`
    .ant-card-actions {
      border-top: 1px solid ${token.colorBorderSecondary};
    }
  `,

  cardTitle: css`
    display: flex;
    align-items: center;
    gap: ${token.paddingXS}px;
  `,

  cardName: css`
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,

  cardDesc: css`
    font-size: ${token.fontSizeSM}px;
    color: ${token.colorTextTertiary};
    > div {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,

  cardTime: css`
    font-size: ${token.fontSizeXS}px;
    color: ${token.colorTextQuaternary};
    margin-top: 2px;
  `,
}));
