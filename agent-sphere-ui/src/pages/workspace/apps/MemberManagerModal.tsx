import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { App, Button, Empty, List, Modal, Select, Tag } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  PROJECT_MEMBER_ROLE,
  PROJECT_MEMBER_ROLE_LABEL_KEYS,
} from '@/constants/project';
import { agentApi } from '@/services/agentSphere/api';
import { tw } from '../i18n';
import { enumLabel } from './labels';

export interface MemberRow {
  userId: number;
  displayName?: string;
  memberRole?: string;
}

export function MemberManagerModal({
  open,
  title,
  load,
  add,
  remove,
  onClose,
  onChanged,
}: {
  open: boolean;
  title: string;
  load: () => Promise<MemberRow[]>;
  add: (userId: number) => Promise<unknown>;
  remove: (userId: number) => Promise<unknown>;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const { message } = App.useApp();
  const [members, setMembers] = useState<MemberRow[]>([]);
  const [contacts, setContacts] = useState<any[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const reload = () => {
    setLoading(true);
    Promise.all([
      load(),
      agentApi.workspaceContacts.users({ page: 1, size: 100 }),
    ])
      .then(([memberRows, contactPage]) => {
        setMembers(memberRows || []);
        setContacts(contactPage?.records || []);
      })
      .catch(() => message.error(tw('projects.loadFailed', 'Failed to load')))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (open) reload();
  }, [open]);

  const existing = useMemo(
    () => new Set(members.map((member) => member.userId)),
    [members],
  );
  const options = useMemo(
    () =>
      contacts
        .filter((contact) => !existing.has(contact.id))
        .map((contact) => ({
          value: contact.id,
          label: contact.displayName || contact.username || `#${contact.id}`,
        })),
    [contacts, existing],
  );
  const nameOf = (userId: number, displayName?: string) => {
    if (displayName) return displayName;
    const contact = contacts.find((row) => row.id === userId);
    return contact?.displayName || contact?.username || `#${userId}`;
  };

  const addMember = async () => {
    if (!selected) return;
    setSaving(true);
    try {
      await add(selected);
      setSelected(null);
      reload();
      onChanged?.();
    } catch {
      message.error(tw('projects.addMemberFailed', 'Failed to add member'));
    } finally {
      setSaving(false);
    }
  };

  const removeMember = async (member: MemberRow) => {
    setSaving(true);
    try {
      await remove(member.userId);
      reload();
      onChanged?.();
    } catch {
      message.error(
        tw('projects.removeMemberFailed', 'Failed to remove member'),
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={open}
      title={title}
      onCancel={onClose}
      footer={null}
      destroyOnHidden
    >
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <Select
          style={{ flex: 1 }}
          value={selected ?? undefined}
          placeholder={tw('projects.selectMember', 'Select a member')}
          options={options}
          showSearch
          optionFilterProp="label"
          onChange={setSelected}
        />
        <Button
          type="primary"
          icon={<PlusOutlined />}
          disabled={!selected}
          loading={saving}
          onClick={() => void addMember()}
        >
          {tw('projects.addMember', 'Add member')}
        </Button>
      </div>
      <List
        loading={loading}
        dataSource={members}
        locale={{
          emptyText: (
            <Empty description={tw('projects.noMembers', 'No members')} />
          ),
        }}
        renderItem={(member) => (
          <List.Item
            actions={[
              <Button
                key="remove"
                type="text"
                danger
                icon={<DeleteOutlined />}
                loading={saving}
                onClick={() => void removeMember(member)}
              />,
            ]}
          >
            <List.Item.Meta
              title={nameOf(member.userId, member.displayName)}
              description={
                <Tag>
                  {enumLabel(
                    PROJECT_MEMBER_ROLE_LABEL_KEYS,
                    member.memberRole || PROJECT_MEMBER_ROLE.MEMBER,
                  )}
                </Tag>
              }
            />
          </List.Item>
        )}
      />
    </Modal>
  );
}
