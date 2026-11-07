import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  detailGrid: css`
    display: grid;
    gap: ${token.paddingSM}px;
  `,

  jsonBlock: css`
    background: ${token.colorBgLayout};
    padding: ${token.paddingSM}px;
    border-radius: ${token.borderRadius}px;
    font-size: ${token.fontSizeSM}px;
    margin-top: ${token.marginXS}px;
    white-space: pre-wrap;
    font-family: ${token.fontFamilyCode};
  `,

  capsTable: css`
    width: 100%;
    border-collapse: collapse;
  `,

  capsTh: css`
    text-align: left;
    padding: ${token.paddingXS}px ${token.paddingSM}px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    font-weight: ${token.fontWeightStrong};
  `,

  capsTd: css`
    padding: ${token.paddingXS}px ${token.paddingSM}px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
  `,

  cardGrid: css`
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    gap: ${token.paddingMD}px;
    padding: ${token.paddingMD}px;
  `,

  card: css`
    .ant-card-actions { border-top: 1px solid ${token.colorBorderSecondary}; }
  `,

  cardTitleWrap: css`
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  `,

  cardTitle: css`
    display: flex;
    align-items: center;
    gap: ${token.paddingXS}px;
    min-width: 0;
  `,

  cardName: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,

  cardTags: css`
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  `,

  cardDesc: css`
    font-size: ${token.fontSizeSM}px;
    color: ${token.colorTextTertiary};
    > div { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `,

  cardTime: css`
    margin-top: ${token.marginXS}px;
    font-size: ${token.fontSizeSM}px;
    color: ${token.colorTextQuaternary};
  `,
}));
