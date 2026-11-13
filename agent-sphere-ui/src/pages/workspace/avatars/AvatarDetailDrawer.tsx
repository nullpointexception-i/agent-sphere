import { CloseOutlined } from '@ant-design/icons';
import { App, Avatar, Button, Drawer, Form, Input, Select, Switch } from 'antd';
import { useEffect, useState } from 'react';
import { STATUS, SUPER_AGENT_STATUS_CODE } from '@/constants/status';
import { imApi } from '@/services/agentSphere/im';
import {
  type SuperAgent,
  superAgentApi,
} from '@/services/agentSphere/superAgent';
import { fullTime, positionLabel, statusLabel } from '../conversationModel';
import { tw } from '../i18n';
import { avatarStyle, initials } from '../shared';
import { useStyles } from '../style';
import { AvatarActions } from './AvatarActions';
import { CloudAvatarPanel } from './CloudAvatarPanel';
import { LocalAvatarPanel } from './LocalAvatarPanel';

interface Props {
  agent: SuperAgent | null;
  open: boolean;
  positionOptions: { value: string; label: string }[];
  onClose: () => void;
  onChanged: () => void;
  onUpdated: (agent: SuperAgent) => void;
  onOpenChat: (conversationId: number) => void;
  onStartTraining: (agent: SuperAgent) => void;
}

export function AvatarDetailDrawer({
  agent,
  open,
  positionOptions,
  onClose,
  onChanged,
  onUpdated,
  onOpenChat,
  onStartTraining,
}: Props) {
  const { styles } = useStyles();
  const { message, modal } = App.useApp();
  const [mode, setMode] = useState<'view' | 'edit'>('view');
  const [editForm] = Form.useForm();
  const editName = Form.useWatch('name', editForm);
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [visibilitySaving, setVisibilitySaving] = useState(false);
  const [startingChat, setStartingChat] = useState(false);

  const isLocal = agent?.agentType === 'LOCAL';

  useEffect(() => {
    if (open) setMode('view');
  }, [open, agent?.id]);

  const startEdit = () => {
    if (!agent) return;
    editForm.setFieldsValue({
      name: agent.name,
      description: agent.description,
      positionCode: agent.positionCode,
    });
    setMode('edit');
  };

  const saveEdit = async () => {
    if (!agent) return;
    const values = await editForm.validateFields();
    setEditSubmitting(true);
    try {
      const updated = await superAgentApi.update(agent.id, {
        name: values.name,
        description: values.description,
        positionCode: values.positionCode,
      });
      message.success(tw('avatars.saved', 'Saved'));
      onUpdated(updated);
      setMode('view');
      onChanged();
    } finally {
      setEditSubmitting(false);
    }
  };

  const startChat = async () => {
    if (!agent || startingChat) return;
    if (agent.status !== STATUS.ACTIVE) {
      message.warning(
        tw(
          'avatars.chatPaused',
          'This avatar is paused. Resume it before chatting.',
        ),
      );
      return;
    }
    setStartingChat(true);
    try {
      const conversation = await imApi.createAgentDm(agent.id);
      onClose();
      onOpenChat(conversation.id);
    } catch {
      message.error(tw('avatars.chatFailed', 'Failed to start chat'));
    } finally {
      setStartingChat(false);
    }
  };

  const startTraining = () => {
    if (!agent) return;
    if (agent.status === SUPER_AGENT_STATUS_CODE.ARCHIVED) {
      message.warning(
        tw('training.archived', 'Archived avatars cannot enter training mode.'),
      );
      return;
    }
    const target = agent;
    onClose();
    onStartTraining(target);
  };

  const toggleVisibility = async (checked: boolean) => {
    if (!agent || visibilitySaving) return;
    setVisibilitySaving(true);
    try {
      const updated = await superAgentApi.updateVisibility(agent.id, checked);
      onUpdated(updated);
      onChanged();
      message.success(
        checked
          ? tw(
              'avatars.publicOn',
              'This avatar is now visible in group creation',
            )
          : tw('avatars.publicOff', 'This avatar is now private'),
      );
    } catch {
      message.error(tw('common.saveFailed', 'Save failed'));
    } finally {
      setVisibilitySaving(false);
    }
  };

  const togglePause = async () => {
    if (!agent) return;
    const updated =
      agent.status === STATUS.ACTIVE
        ? await superAgentApi.pause(agent.id)
        : await superAgentApi.resume(agent.id);
    onUpdated(updated);
    onChanged();
  };

  const removeAgent = () => {
    if (!agent) return;
    modal.confirm({
      title: tw('avatars.deleteTitle', 'Delete {name}', { name: agent.name }),
      content: tw(
        'avatars.deleteContent',
        'The avatar will be archived after deletion. Continue?',
      ),
      okText: tw('common.delete', 'Delete'),
      cancelText: tw('avatars.keep', 'Keep it'),
      okType: 'danger',
      onOk: async () => {
        await superAgentApi.delete(agent.id);
        message.success(tw('avatars.deleted', 'Deleted'));
        onClose();
        onChanged();
      },
    });
  };

  return (
    <Drawer
      open={open}
      placement="right"
      width={440}
      closable={false}
      onClose={onClose}
      destroyOnHidden
      rootClassName={styles.avatarDrawerRoot}
      getContainer={() =>
        document.getElementById('workspace-main') ?? document.body
      }
      styles={{ body: { padding: 0 } }}
    >
      <div className={styles.avatarDrawerBody}>
        <div className={styles.avatarDetailHeader}>
          <Avatar
            className={styles.avatarDetailAvatar}
            size={52}
            shape="square"
            style={avatarStyle(
              mode === 'edit' ? editName || agent?.name : agent?.name,
            )}
          >
            {initials(mode === 'edit' ? editName || agent?.name : agent?.name)}
          </Avatar>
          <div className={styles.avatarDetailHeadText}>
            <div className={styles.avatarDetailName}>
              {mode === 'view'
                ? agent?.name || tw('nav.avatars', 'Avatars')
                : tw('avatars.editTitle', 'Edit avatar')}
            </div>
            <div className={styles.avatarDetailSub}>
              {mode === 'view'
                ? tw('avatars.detailTitle', 'Avatar details')
                : tw(
                    'avatars.editSubtitle',
                    'Change the name and responsibility',
                  )}
            </div>
          </div>
          <button
            type="button"
            className={styles.avatarDetailClose}
            onClick={onClose}
          >
            <CloseOutlined />
          </button>
        </div>

        {mode === 'view' ? (
          <>
            <div className={styles.avatarDetailSection}>
              <div className={styles.avatarDetailLabel}>
                {tw('avatars.position', 'Role')}
              </div>
              <span className={styles.avatarRoleTag}>
                {positionLabel(agent) || tw('avatars.positionUnset', 'Not set')}
              </span>
            </div>

            <div className={styles.avatarDetailSection}>
              <div className={styles.avatarDetailLabel}>
                {tw('avatars.desc', 'Responsibility')}
              </div>
              <div className={styles.avatarDetailValue}>
                {agent?.description ||
                  tw('common.noDescription', 'No description')}
              </div>
            </div>

            <div className={styles.avatarDetailSection}>
              <div className={styles.avatarDetailLabel}>
                {tw('avatars.status', 'Status')}
              </div>
              <div className={styles.avatarStatusRow}>
                <span
                  className={`${styles.avatarStatusDot} ${
                    agent?.status === STATUS.ACTIVE
                      ? ''
                      : styles.avatarStatusDotPaused
                  }`}
                />
                {statusLabel(agent?.status)}
              </div>
            </div>

            {agent ? (
              isLocal ? (
                <LocalAvatarPanel agent={agent} onUpdated={onUpdated} />
              ) : (
                <CloudAvatarPanel agent={agent} />
              )
            ) : null}

            <div className={styles.avatarDetailSection}>
              <div className={styles.avatarDetailLabel}>
                {tw('avatars.publicVisible', 'Visible in group creation')}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Switch
                  checked={Boolean(agent?.publicVisible)}
                  loading={visibilitySaving}
                  disabled={agent?.status === SUPER_AGENT_STATUS_CODE.ARCHIVED}
                  onChange={(checked) => void toggleVisibility(checked)}
                />
                <span style={{ fontSize: 12, color: '#8c8c8c' }}>
                  {tw(
                    'avatars.publicVisibleHint',
                    'Other members of this tenant can add the avatar to a group chat.',
                  )}
                </span>
              </div>
            </div>

            <div className={styles.avatarDetailSection}>
              <div className={styles.avatarDetailLabel}>
                {tw('avatars.createdAtLabel', 'Created')}
              </div>
              <div className={styles.avatarDetailValue}>
                {fullTime(agent?.createdAt)}
              </div>
            </div>

            {agent && agent.status !== STATUS.ACTIVE ? (
              <div className={styles.avatarPausedHint}>
                {tw(
                  'avatars.chatPaused',
                  'This avatar is paused. Resume it before chatting.',
                )}
              </div>
            ) : null}

            {agent ? (
              <AvatarActions
                agent={agent}
                startingChat={startingChat}
                onChat={() => void startChat()}
                onEdit={startEdit}
                onToggle={() => void togglePause()}
                onDelete={removeAgent}
                onTraining={isLocal ? undefined : startTraining}
              />
            ) : null}
          </>
        ) : (
          <>
            <Form
              form={editForm}
              layout="vertical"
              className={styles.avatarEditForm}
            >
              <div className={styles.fieldBlock}>
                <div className={styles.fieldLabel}>
                  {tw('avatars.name', 'Name')}{' '}
                  <span className={styles.required}>*</span>
                </div>
                <Form.Item
                  name="name"
                  rules={[{ required: true, max: 128 }]}
                  style={{ marginBottom: 0 }}
                >
                  <Input
                    className={styles.softField}
                    maxLength={32}
                    placeholder={tw(
                      'avatars.namePlaceholder',
                      'e.g. Product assistant',
                    )}
                  />
                </Form.Item>
              </div>

              <div className={styles.fieldBlock}>
                <div className={styles.fieldLabel}>
                  {tw('avatars.position', 'Role')}{' '}
                  <span className={styles.required}>*</span>
                </div>
                <Form.Item
                  name="positionCode"
                  rules={[
                    {
                      required: true,
                      message: tw('avatars.positionRequired', 'Select a role'),
                    },
                  ]}
                  style={{ marginBottom: 0 }}
                >
                  <Select
                    className={styles.softField}
                    placeholder={tw(
                      'avatars.positionPlaceholder',
                      'Select a role',
                    )}
                    options={positionOptions}
                  />
                </Form.Item>
              </div>

              <div className={styles.fieldBlock} style={{ marginBottom: 10 }}>
                <div className={styles.fieldLabel}>
                  {tw('avatars.desc', 'Responsibility')}{' '}
                  <span className={styles.optional}>
                    {tw('common.optional', 'Optional')}
                  </span>
                </div>
                <Form.Item name="description" style={{ marginBottom: 14 }}>
                  <Input.TextArea
                    className={styles.softTextarea}
                    rows={4}
                    maxLength={200}
                    showCount
                    placeholder={tw(
                      'avatars.descPlaceholder',
                      'Briefly describe what it is good at',
                    )}
                  />
                </Form.Item>
                <div className={styles.fieldHint}>
                  {tw(
                    'avatars.editHint',
                    'Changes take effect on the next run',
                  )}
                </div>
              </div>
            </Form>

            <div className={styles.avatarEditFooter}>
              <Button
                className={styles.avatarCancelButton}
                onClick={() => setMode('view')}
              >
                {tw('common.cancel', 'Cancel')}
              </Button>
              <Button
                type="primary"
                className={styles.avatarSaveButton}
                loading={editSubmitting}
                onClick={() => void saveEdit()}
              >
                {tw('avatars.saveEdit', 'Save changes')}
              </Button>
            </div>
          </>
        )}
      </div>
    </Drawer>
  );
}
