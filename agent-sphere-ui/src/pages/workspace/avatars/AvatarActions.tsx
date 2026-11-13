import {
  DeleteOutlined,
  EditOutlined,
  ExperimentOutlined,
  MessageOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { Button } from 'antd';
import { STATUS } from '@/constants/status';
import type { SuperAgent } from '@/services/agentSphere/superAgent';
import { tw } from '../i18n';
import { useStyles } from '../style';

interface Props {
  agent: SuperAgent;
  startingChat: boolean;
  onChat: () => void;
  onEdit: () => void;
  onToggle: () => void;
  onDelete: () => void;
  onTraining?: () => void;
}

export function AvatarActions({
  agent,
  startingChat,
  onChat,
  onEdit,
  onToggle,
  onDelete,
  onTraining,
}: Props) {
  const { styles } = useStyles();
  return (
    <div className={styles.avatarDetailFooter}>
      <Button
        type="primary"
        block
        icon={<MessageOutlined />}
        loading={startingChat}
        disabled={agent.status !== STATUS.ACTIVE}
        className={styles.avatarDetailPrimary}
        onClick={onChat}
      >
        {tw('avatars.startChat', 'Start chat')}
      </Button>
      {onTraining ? (
        <Button
          block
          icon={<ExperimentOutlined />}
          style={{ marginTop: 8 }}
          onClick={onTraining}
        >
          {tw('training.open', 'Training mode')}
        </Button>
      ) : null}
      <div className={styles.avatarSecondaryActions}>
        <button
          type="button"
          className={`${styles.avatarSecondaryButton} ${styles.avatarEditButton}`}
          onClick={onEdit}
        >
          <EditOutlined />
          {tw('avatars.editProfile', 'Edit profile')}
        </button>
        <button
          type="button"
          className={`${styles.avatarSecondaryButton} ${styles.avatarPauseButton}`}
          onClick={onToggle}
        >
          {agent.status === STATUS.ACTIVE ? (
            <PauseCircleOutlined />
          ) : (
            <PlayCircleOutlined />
          )}
          {agent.status === STATUS.ACTIVE
            ? tw('avatars.pause', 'Pause avatar')
            : tw('avatars.resume', 'Resume avatar')}
        </button>
        <button
          type="button"
          className={`${styles.avatarSecondaryButton} ${styles.avatarDeleteButton}`}
          onClick={onDelete}
        >
          <DeleteOutlined />
          {tw('avatars.delete', 'Delete avatar')}
        </button>
      </div>
    </div>
  );
}
