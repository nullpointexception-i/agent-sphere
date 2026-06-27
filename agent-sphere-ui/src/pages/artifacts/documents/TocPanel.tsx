import { useStyles } from './style';

interface TocItemBase {
  level: number;
  text: string;
}

function TocPanel<T extends TocItemBase>({
  items,
  onJump,
  visible,
  onClose,
}: {
  items: T[];
  onJump: (item: T) => void;
  visible: boolean;
  onClose: () => void;
}) {
  const { styles } = useStyles();
  if (!visible) return null;
  return (
    <div className={styles.tocPanel}>
      <div className={styles.tocHeader}>
        <span>Outline</span>
        <a onClick={onClose} className={styles.tocClose}>
          ✕
        </a>
      </div>
      {items.length === 0 && <div className={styles.tocEmpty}>No headings</div>}
      {items.map((item, i) => (
        <div
          key={i}
          onClick={() => onJump(item)}
          className={`${styles.tocItem}${item.level === 1 ? ` ${styles.tocItemLevel1}` : ''}`}
          style={{ paddingLeft: 12 + (item.level - 1) * 16 }}
        >
          {item.text}
        </div>
      ))}
    </div>
  );
}

export type { TocItemBase };
export { TocPanel };
