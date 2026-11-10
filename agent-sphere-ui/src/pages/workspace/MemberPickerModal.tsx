import { CheckOutlined } from '@ant-design/icons';
import { Avatar, Button, Empty, Input, Modal } from 'antd';
import { useMemo, useState } from 'react';
import { avatarStyle, initials } from './shared';
import { useStyles } from './style';

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
 * Two-pane member picker used by the group settings card actions: searchable contacts on the
 * left, the current selection on the right. Mirrors the add/remove member flow.
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
      <div className={styles.pickerBody}>
        <div className={styles.pickerPane}>
          <Input.Search
            allowClear
            value={keyword}
            placeholder={searchPlaceholder}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <div className={styles.pickerList}>
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
                    className={styles.pickerRow}
                    onClick={() => onToggle(item.id)}
                  >
                    <span
                      className={`${styles.pickerCheck} ${
                        checked ? styles.pickerCheckActive : ''
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
                    <span className={styles.pickerName}>{item.name}</span>
                  </button>
                );
              })
            )}
          </div>
        </div>
        <div className={`${styles.pickerPane} ${styles.pickerSelectedPane}`}>
          <div className={styles.pickerSelectedTitle}>
            {selectedTitle} · {selectedItems.length}
          </div>
          <div className={styles.pickerList}>
            {selectedItems.length === 0 ? (
              <div className={styles.settingHint}>{noSelectedText}</div>
            ) : (
              selectedItems.map((item) => (
                <button
                  type="button"
                  key={item.id}
                  className={styles.pickerRow}
                  onClick={() => onToggle(item.id)}
                >
                  <Avatar
                    size={36}
                    src={item.avatar}
                    style={avatarStyle(item.name)}
                  >
                    {initials(item.name)}
                  </Avatar>
                  <span className={styles.pickerName}>{item.name}</span>
                </button>
              ))
            )}
          </div>
        </div>
      </div>
      <div className={styles.pickerFooter}>
        <Button className={styles.ghostPill} onClick={onCancel}>
          {cancelText}
        </Button>
        <Button
          type="primary"
          className={styles.primaryPill}
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
