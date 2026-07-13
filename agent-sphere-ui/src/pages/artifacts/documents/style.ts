import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  container: css`
    overflow: hidden;
    display: flex;
    flex-direction: column;
    max-width: 1400px;
    margin: 0 auto;
    padding: 0 32px 16px;
  `,
  containerDetail: css`
    overflow: hidden;
    display: flex;
    flex-direction: column;
    max-width: 1200px;
    margin: 0 auto;
    padding: 0 32px 16px;
  `,
  spinWrapper: css`
    flex: 1;
    display: flex;
    justify-content: center;
    align-items: center;
  `,
  headerBarEdit: css`
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-top: 24px;
    padding-bottom: 8px;
  `,
  headerBarDetail: css`
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-top: 24px;
    padding-bottom: 12px;
  `,
  headerRight: css`
    display: flex;
    gap: 8px;
    align-items: center;
  `,
  backBtn: css`
    padding: 0;
  `,
  tocPanel: css`
    width: 200px;
    flex-shrink: 0;
    border-left: 1px solid #e8e8e8;
    padding: 12px 0;
    overflow-y: auto;
    max-height: calc(100vh - 280px);
    position: sticky;
    top: 16px;
    align-self: flex-start;
    background: #fafafa;
  `,
  tocHeader: css`
    padding: 0 12px 8px;
    font-size: 12px;
    color: #999;
    display: flex;
    justify-content: space-between;
  `,
  tocClose: css`
    cursor: pointer;
  `,
  tocEmpty: css`
    padding: 0 12px;
    font-size: 12px;
    color: #ccc;
  `,
  tocItem: css`
    font-size: 13px;
    cursor: pointer;
    color: #333;
    line-height: 1.6;
    padding: 4px 12px 4px;
    &:hover {
      background: #e6f4ff;
    }
  `,
  tocItemLevel1: css`
    border-left: 2px solid #1677ff;
  `,
  titleInput: css`
    font-size: 18px;
    font-weight: 600;
    flex-shrink: 0;
    border: none;
    border-bottom: 1px solid #e8e8e8;
    border-radius: 0;
    padding: 8px 0 12px;
  `,
  editorWrapper: css`
    flex: 1;
    overflow: hidden;
    display: flex;
    gap: 0;
    border: 1px solid #e8e8e8;
    border-radius: 6px;
  `,
  editorColumn: css`
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
    overflow: hidden;
  `,
  toolbar: css`
    position: sticky;
    top: 0;
    z-index: 10;
    background: #fafafa;
    border-bottom: 1px solid #e8e8e8;
    border-top-left-radius: 6px;
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
    padding: 6px 8px;
    flex-shrink: 0;
  `,
  editorScroll: css`
    flex: 1;
    overflow-y: auto;
    overscroll-behavior: contain;
    min-height: 0;

    .tiptap-editor {
      min-height: 400px;
      padding: 16px;
      outline: none;
    }
    .tiptap-editor table {
      border-collapse: collapse;
      width: 100%;
      margin: 8px 0;
    }
    .tiptap-editor th,
    .tiptap-editor td {
      border: 1px solid #d9d9d9;
      padding: 6px 10px;
      text-align: left;
      vertical-align: top;
    }
    .tiptap-editor th {
      background: #fafafa;
      font-weight: 600;
    }
    .tiptap-editor p {
      margin: 0;
    }
  `,
  toolbarBtn: css`
    margin-right: 2px;
  `,
  charCountBar: css`
    flex-shrink: 0;
    text-align: right;
    font-size: 12px;
    color: #999;
    padding: 4px 12px;
    border-top: 1px solid #e8e8e8;
    background: #fafafa;
    border-bottom-left-radius: 6px;
    border-bottom-right-radius: 6px;
  `,
  sharedContainer: css`
    max-width: 800px;
    margin: 0 auto;
    padding: 48px 24px;
  `,
  notFound: css`
    padding: 32px;
  `,
  contentFlex: css`
    display: flex;
    gap: 0;
    flex: 1;
    overflow: hidden;
  `,
  contentCard: css`
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    padding: 24px;
    background: #fff;
    border-radius: 8px;
    border: 1px solid #e8e8e8;
  `,
  metaBar: css`
    flex-shrink: 0;
  `,
  metaInfo: css`
    font-size: 12px;
    color: #999;
    margin-bottom: 16px;
  `,
  markdownScroll: css`
    flex: 1;
    overflow-y: auto;
    overscroll-behavior: contain;
    min-height: 0;
    border-top: 1px solid #e8e8e8;
    padding-top: 16px;
  `,
}));
