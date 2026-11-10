import { CheckOutlined } from '@ant-design/icons';
import { Avatar, Button, Empty, Input, Modal } from 'antd';
import { createStyles } from 'antd-style';
import { useMemo, useState } from 'react';

const AVATAR_PALETTE = [
  'linear-gradient(135deg, #8b7bff, #5b8cff)',
  'linear-gradient(135deg, #ff9f8a, #ff6b8a)',
  'linear-gradient(135deg, #5ec9c0, #3aa6a0)',
  'linear-gradient(135deg, #ffc46b, #e8a33d)',
  'linear-gradient(135deg, #9d8bff, #c86dd7)',
  'linear-gradient(135deg, #67b8ff, #3f8cff)',
];

function initials(name?: string) {
  return (name || '?').trim().slice(0, 1).toUpperCase() || '?';
}

function avatarStyle(name?: string) {
  const text = (name || '?').trim();
  let hash = 0;
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 31 + text.charCodeAt(index)) % 997;
  }
  return { backgroundImage: AVATAR_PALETTE[hash % AVATAR_PALETTE.length] };
}

const useStyles = createStyles(({ css }) => ({
  body: css`
    display: grid;
    grid-template-columns: minmax(0, 1fr) 220px;
    gap: 16px;
    height: 440px;
    margin-top: 8px;
  `,
  pane: css`
    display: flex;
    flex-direction: column;
    min-height: 0;
    gap: 10px;
  `,
  selectedPane: css`
    border-left: 1px solid #f4f2fb;
    padding-left: 16px;
  `,
  list: css`
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding-right: 2px;
  `,
  row: css`
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 6px;
    border: none;
    background: none;
    border-radius: 10px;
    cursor: pointer;
    text-align: left;
    &:hover {
      background: #faf9ff;
    }
  `,
  check: css`
    width: 18px;
    height: 18px;
    flex: none;
    border-radius: 50%;
    border: 1.5px solid #ddd8ee;
    color: #fff;
    font-size: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
  `,
  checkActive: css`
    border-color: #6c5ce7;
    background: #6c5ce7;
  `,
  name: css`
    flex: 1;
    min-width: 0;
    color: #3a3358;
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  selectedTitle: css`
    color: #3a3358;
    font-size: 12px;
    font-weight: 400;
  `,
  footer: css`
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    margin-top: 16px;
  `,
  emptyHint: css`
    margin-top: 6px;
    color: #a7a2b8;
    font-size: 12px;
  `,
}));

export interface MemberPickerItem {
  id: number;
  name: string;
  avatar?: string;
}

interface MemberPickerModalProps {
  open: boolean;
  title: string;
  selectedTitle: string;
  confirmText: string;
  cancelText: string;
  searchPlaceholder: string;
  emptyText: string;
  noSelectedText: string;
  items: MemberPickerItem[];
  selected: number[];
  confirming?: boolean;
  onToggle: (id: number) => void;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * Two-pane member picker shared by the workspace and console group settings: searchable
 * candidates on the left, the current selection on the right.
 */
export default function MemberPickerModal({
  open,
  title,
  selectedTitle,
  confirmText,
  cancelText,
  searchPlaceholder,
  emptyText,
  noSelectedText,
  items,
  selected,
  confirming,
  onToggle,
  onCancel,
  onConfirm,
}: MemberPickerModalProps) {
  const { styles } = useStyles();
  const [keyword, setKeyword] = useState('');

  const selectedIds = useMemo(() => new Set(selected), [selected]);
  const filtered = useMemo(() => {
    const text = keyword.trim().toLowerCase();
    if (!text) return items;
    return items.filter((item) => item.name.toLowerCase().includes(text));
  }, [items, keyword]);
  const selectedItems = useMemo(
    () => items.filter((item) => selectedIds.has(item.id)),
    [items, selectedIds],
  );

  return (
    <Modal
      open={open}
      title={title}
      width={720}
      footer={null}
      onCancel={onCancel}
      destroyOnHidden
      afterClose={() => setKeyword('')}
    >
      <div className={styles.body}>
        <div className={styles.pane}>
          <Input.Search
            allowClear
            value={keyword}
            placeholder={searchPlaceholder}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <div className={styles.list}>
            {filtered.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={emptyText}
                style={{ marginTop: 48 }}
              />
            ) : (
              filtered.map((item) => {
                const checked = selectedIds.has(item.id);
                return (
                  <button
                    type="button"
                    key={item.id}
                    className={styles.row}
                    onClick={() => onToggle(item.id)}
                  >
                    <span
                      className={`${styles.check} ${
                        checked ? styles.checkActive : ''
                      }`}
                    >
                      {checked ? <CheckOutlined /> : null}
                    </span>
                    <Avatar
                      size={36}
                      src={item.avatar}
                      style={avatarStyle(item.name)}
                    >
                      {initials(item.name)}
                    </Avatar>
                    <span className={styles.name}>{item.name}</span>
                  </button>
                );
              })
            )}
          </div>
        </div>
        <div className={`${styles.pane} ${styles.selectedPane}`}>
          <div className={styles.selectedTitle}>
            {selectedTitle} · {selectedItems.length}
          </div>
          <div className={styles.list}>
            {selectedItems.length === 0 ? (
              <div className={styles.emptyHint}>{noSelectedText}</div>
            ) : (
              selectedItems.map((item) => (
                <button
                  type="button"
                  key={item.id}
                  className={styles.row}
                  onClick={() => onToggle(item.id)}
                >
                  <Avatar
                    size={36}
                    src={item.avatar}
                    style={avatarStyle(item.name)}
                  >
                    {initials(item.name)}
                  </Avatar>
                  <span className={styles.name}>{item.name}</span>
                </button>
              ))
            )}
          </div>
        </div>
      </div>
      <div className={styles.footer}>
        <Button onClick={onCancel}>{cancelText}</Button>
        <Button
          type="primary"
          disabled={selected.length === 0}
          loading={confirming}
          onClick={onConfirm}
        >
          {confirmText}
        </Button>
      </div>
    </Modal>
  );
}
