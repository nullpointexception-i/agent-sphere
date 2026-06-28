import { PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Drawer,
  Form,
  Input,
  Modal,
  Table,
  Tabs,
  Tag,
  Upload,
} from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { labelWithRule } from '@/utils/labelWithRule';
import CapabilityPicker from './CapabilityPicker';

interface Props {
  record: any;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export default function InstanceEditDrawer({
  record,
  open,
  onClose,
  onSaved,
}: Props) {
  const { message } = App.useApp();
  const intl = useIntl();
  const locale = intl.locale;
  const builtinDisplay = (tool: any) =>
    locale === 'en-US'
      ? tool?.displayNameEn || tool?.name || ''
      : tool?.displayNameCn || tool?.name || '';
  const [editTab, setEditTab] = useState('info');
  const [imagePreview, setImagePreview] = useState('');
  const [form] = Form.useForm();
  const [boundCaps, setBoundCaps] = useState<any[]>([]);
  const [addCaps, setAddCaps] = useState<any[]>([]);
  const [addingType, setAddingType] = useState<string | null>(null);
  const [allBuiltin, setAllBuiltin] = useState<any[]>([]);
  const [toRemoveIds, setToRemoveIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (!open) return;
    setEditTab('info');
    setImagePreview(record.image || '');
    form.setFieldsValue(record);
    setAddCaps([]);
    setToRemoveIds(new Set());
    setAddingType(null);
    agentApi.instanceCapabilities
      .listFull(record.id)
      .then(setBoundCaps)
      .catch(() => setBoundCaps([]));
    agentApi.builtin
      .list()
      .then(setAllBuiltin)
      .catch(() => setAllBuiltin([]));
  }, [open, record]);

  const markRemove = (id: number) => {
    setToRemoveIds((prev) => new Set(prev).add(id));
  };

  const unmarkRemove = (id: number) => {
    setToRemoveIds((prev) => {
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
  };

  const submit = async () => {
    const values = await form.validateFields();
    const payload = { ...values, image: imagePreview || undefined };
    await agentApi.instances.update(record.id, payload);
    for (const id of toRemoveIds) {
      await agentApi.instanceCapabilities.delete(id);
    }
    const items = addCaps.map((c: any) => ({
      instanceId: record.id,
      capabilityType: c.capabilityType,
      capabilityId: c.id,
    }));
    if (items.length > 0) {
      await agentApi.instanceCapabilities.batchCreate({ capabilities: items });
    }
    message.success('Saved');
    onClose();
    onSaved();
  };

  const capTypeLabel: Record<string, string> = {
    mcp: 'MCP',
    skill: intl.formatMessage({ id: 'menu.capabilities.skill' }),
    cli: 'CLI',
    builtin: intl.formatMessage({ id: 'menu.capabilities.builtin' }),
  };

  const capTypes = ['mcp', 'skill', 'cli', 'builtin'];

  const fetchMap: Record<string, (params: any) => Promise<any>> = {
    mcp: (p) => agentApi.mcp.list(p),
    skill: (p) => agentApi.skill.list(p),
    cli: (p) => agentApi.cli.list(p),
  };

  const builtinName = (id: number) => {
    const tool = allBuiltin.find((b) => b.id === id);
    return builtinDisplay(tool) || `#${id}`;
  };

  const toggleBuiltinPick = (id: number) => {
    if (addCaps.some((c) => c.capabilityType === 'builtin' && c.id === id)) {
      setAddCaps((prev) =>
        prev.filter((c) => !(c.capabilityType === 'builtin' && c.id === id)),
      );
    } else {
      setAddCaps((prev) => [...prev, { id, capabilityType: 'builtin' }]);
    }
  };

  const bindName = (cap: any) => {
    if (cap.capabilityType === 'builtin') {
      return builtinName(cap.capabilityId ?? cap.id);
    }
    if (cap.name) return cap.name;
    return `#${cap.capabilityId}`;
  };

  const selectedCaps = (type: string) => {
    const ids = new Set(
      addCaps.filter((c) => c.capabilityType === type).map((c) => c.id),
    );
    const boundIds = new Set(
      boundCaps
        .filter((c) => c.capabilityType === type)
        .map((c) => c.capabilityId),
    );
    return { ids, boundIds };
  };

  const onPick = (type: string, item: any) => {
    setAddCaps((prev) => [
      ...prev,
      { id: item.id, name: item.name, capabilityType: type },
    ]);
  };

  const onUnpick = (type: string, item: any) => {
    setAddCaps((prev) =>
      prev.filter((c) => !(c.capabilityType === type && c.id === item.id)),
    );
  };

  const removeAdded = (type: string, id: number) => {
    setAddCaps((prev) =>
      prev.filter((c) => !(c.capabilityType === type && c.id === id)),
    );
  };

  return (
    <>
      <Drawer
        title={record?.name}
        open={open}
        onClose={onClose}
        size="large"
        extra={
          <Button type="primary" onClick={submit}>
            {intl.formatMessage({ id: 'pages.save', defaultMessage: 'Save' })}
          </Button>
        }
      >
        <Tabs
          activeKey={editTab}
          onChange={setEditTab}
          items={[
            {
              key: 'info',
              label: intl.formatMessage({
                id: 'pages.instances.tabInfo',
                defaultMessage: 'Info',
              }),
              children: (
                <Form form={form} layout="vertical">
                  <Form.Item
                    name="name"
                    label={labelWithRule(
                      intl.formatMessage({ id: 'pages.form.name' }),
                      intl.formatMessage({ id: 'pages.hint.name' }),
                    )}
                    rules={[{ required: true }]}
                  >
                    <Input maxLength={64} />
                  </Form.Item>
                  <Form.Item
                    label={intl.formatMessage({
                      id: 'pages.instances.image',
                      defaultMessage: 'Image',
                    })}
                  >
                    <Upload
                      listType="picture-card"
                      showUploadList={false}
                      accept="image/*"
                      beforeUpload={(file) => {
                        if (file.size > 1 * 1024 * 1024) {
                          message.error(
                            intl.formatMessage({
                              id: 'pages.upload.fileTooLarge',
                              defaultMessage: 'File size must not exceed 1MB',
                            }),
                          );
                          return false;
                        }
                        const reader = new FileReader();
                        reader.onload = (e) =>
                          setImagePreview(e.target?.result as string);
                        reader.readAsDataURL(file);
                        return false;
                      }}
                    >
                      {imagePreview ? (
                        <img
                          src={imagePreview}
                          alt="preview"
                          style={{
                            width: '100%',
                            height: '100%',
                            objectFit: 'cover',
                          }}
                        />
                      ) : (
                        <div>
                          <UploadOutlined />
                          <div style={{ marginTop: 4 }}>Upload</div>
                        </div>
                      )}
                    </Upload>
                  </Form.Item>
                  <Form.Item
                    name="description"
                    label={labelWithRule(
                      intl.formatMessage({ id: 'pages.form.description' }),
                      intl.formatMessage({ id: 'pages.hint.description' }),
                    )}
                  >
                    <Input.TextArea rows={2} maxLength={255} />
                  </Form.Item>
                  <Form.Item
                    name="systemPrompt"
                    label={labelWithRule(
                      intl.formatMessage({
                        id: 'pages.instances.systemPrompt',
                      }),
                      intl.formatMessage({ id: 'pages.hint.text' }),
                    )}
                  >
                    <Input.TextArea rows={4} maxLength={5000} />
                  </Form.Item>
                </Form>
              ),
            },
            {
              key: 'capabilities',
              label: intl.formatMessage({
                id: 'pages.instances.tabCapabilities',
                defaultMessage: 'Capabilities',
              }),
              children: (
                <div>
                  <div
                    style={{
                      marginBottom: 12,
                      padding: '8px 12px',
                      background: '#fff7e6',
                      borderRadius: 6,
                      fontSize: 13,
                      color: '#d46b08',
                    }}
                  >
                    {intl.formatMessage({
                      id: 'pages.instances.capabilitiesSaveHint',
                      defaultMessage:
                        'Changes take effect only after clicking Save.',
                    })}
                  </div>
                  <Tabs
                    tabPlacement="left"
                    items={capTypes.map((type) => {
                      const bound = boundCaps.filter(
                        (c) => c.capabilityType === type,
                      );
                      const added = addCaps.filter(
                        (c) => c.capabilityType === type,
                      );
                      const autoBuiltinIds = new Set(
                        allBuiltin
                          .filter((b) => b.needConfig === false)
                          .map((b) => b.id),
                      );
                      const autoRows =
                        type === 'builtin'
                          ? allBuiltin
                              .filter((b) => b.needConfig === false)
                              .map((b) => ({
                                id: `auto-${b.id}`,
                                capabilityId: b.id,
                                capabilityType: 'builtin',
                                name: b.name,
                                displayNameCn: b.displayNameCn,
                                displayNameEn: b.displayNameEn,
                                _auto: true,
                              }))
                          : [];
                      const userBound =
                        type === 'builtin'
                          ? bound.filter(
                              (b) => !autoBuiltinIds.has(b.capabilityId),
                            )
                          : bound;
                      return {
                        key: type,
                        label: capTypeLabel[type],
                        children: (
                          <div>
                            <div
                              style={{
                                marginBottom: 12,
                                display: 'flex',
                                justifyContent: 'space-between',
                                alignItems: 'center',
                              }}
                            >
                              <span style={{ fontWeight: 500 }}>
                                {intl.formatMessage({
                                  id: 'pages.instances.currentCapabilities',
                                  defaultMessage: 'Current',
                                })}
                              </span>
                              <Button
                                size="small"
                                type="primary"
                                icon={<PlusOutlined />}
                                onClick={() => setAddingType(type)}
                              >
                                {intl.formatMessage({
                                  id: 'pages.instances.addCapability',
                                  defaultMessage: 'Add',
                                })}
                              </Button>
                            </div>
                            <Table
                              rowKey={(r: any) =>
                                r._auto
                                  ? `auto-${r.capabilityId}`
                                  : r.capabilityId != null
                                    ? `${type}-${r.capabilityId}`
                                    : `add-${r.id}`
                              }
                              dataSource={[...autoRows, ...userBound, ...added]}
                              size="small"
                              pagination={false}
                              locale={{
                                emptyText: intl.formatMessage({
                                  id: 'pages.table.empty',
                                }),
                              }}
                              columns={[
                                {
                                  title: intl.formatMessage({
                                    id: 'pages.table.name',
                                  }),
                                  dataIndex: 'name',
                                  ellipsis: true,
                                  render: (_: any, r: any) => {
                                    if (r._auto)
                                      return (
                                        <span
                                          style={{
                                            fontStyle: 'italic',
                                            color: '#666',
                                          }}
                                        >
                                          {bindName(r)}
                                        </span>
                                      );
                                    const removed =
                                      r.capabilityId != null &&
                                      toRemoveIds.has(r.id);
                                    return (
                                      <span
                                        style={
                                          removed
                                            ? {
                                                textDecoration: 'line-through',
                                                color: '#999',
                                              }
                                            : undefined
                                        }
                                      >
                                        {bindName(r)}
                                      </span>
                                    );
                                  },
                                },
                                {
                                  title: '',
                                  width: 80,
                                  render: (_: any, r: any) =>
                                    r._auto ? (
                                      <Tag
                                        style={{
                                          fontSize: 10,
                                          lineHeight: '16px',
                                        }}
                                        color="blue"
                                      >
                                        {intl.formatMessage({
                                          id: 'pages.instances.autoInclude',
                                          defaultMessage: 'Built-in',
                                        })}
                                      </Tag>
                                    ) : (
                                      <Button
                                        type="link"
                                        danger={!toRemoveIds.has(r.id)}
                                        size="small"
                                        onClick={() => {
                                          if (
                                            r.capabilityId != null &&
                                            r.capabilityType
                                          ) {
                                            if (toRemoveIds.has(r.id)) {
                                              unmarkRemove(r.id);
                                            } else {
                                              markRemove(r.id);
                                            }
                                          } else {
                                            removeAdded(type, r.id);
                                          }
                                        }}
                                      >
                                        {toRemoveIds.has(r.id) &&
                                        r.capabilityId != null
                                          ? intl.formatMessage({
                                              id: 'pages.instances.undoRemove',
                                              defaultMessage: 'Undo',
                                            })
                                          : intl.formatMessage({
                                              id: 'pages.instances.removeCapability',
                                              defaultMessage: 'Remove',
                                            })}
                                      </Button>
                                    ),
                                },
                              ]}
                            />
                          </div>
                        ),
                      };
                    })}
                  />
                </div>
              ),
            },
          ]}
        />
      </Drawer>
      {addingType && addingType !== 'builtin' && (
        <Modal
          title={`${intl.formatMessage({ id: 'pages.instances.addCapability', defaultMessage: 'Add' })} - ${capTypeLabel[addingType]}`}
          open
          onCancel={() => setAddingType(null)}
          footer={null}
          width={800}
        >
          <CapabilityPicker
            fetchFn={fetchMap[addingType]}
            boundIds={selectedCaps(addingType).boundIds}
            addedIds={selectedCaps(addingType).ids}
            onSelect={(item) => onPick(addingType, item)}
            onDeselect={(item) => onUnpick(addingType, item)}
          />
        </Modal>
      )}
      {addingType === 'builtin' &&
        (() => {
          const already = new Set(
            addCaps
              .filter((c) => c.capabilityType === 'builtin')
              .map((c) => c.id),
          );
          const bound = new Set(
            boundCaps
              .filter((c) => c.capabilityType === 'builtin')
              .map((c) => c.capabilityId),
          );
          return (
            <Modal
              title={`${intl.formatMessage({ id: 'pages.instances.addCapability', defaultMessage: 'Add' })} - ${capTypeLabel.builtin}`}
              open
              footer={null}
              onCancel={() => setAddingType(null)}
            >
              <Table
                rowKey="id"
                dataSource={allBuiltin.filter((b) => b.needConfig !== false)}
                size="small"
                pagination={false}
                locale={{
                  emptyText: intl.formatMessage({ id: 'pages.table.empty' }),
                }}
                columns={[
                  {
                    title: intl.formatMessage({ id: 'pages.table.name' }),
                    dataIndex: 'name',
                    ellipsis: true,
                    render: (_: any, record: any) => builtinDisplay(record),
                  },
                  {
                    title: intl.formatMessage({
                      id: 'pages.table.description',
                    }),
                    dataIndex: 'description',
                    ellipsis: true,
                  },
                  {
                    title: '',
                    width: 90,
                    render: (_: any, record: any) => (
                      <Button
                        size="small"
                        disabled={
                          bound.has(record.id) || already.has(record.id)
                        }
                        type={already.has(record.id) ? 'primary' : 'default'}
                        onClick={() => toggleBuiltinPick(record.id)}
                      >
                        {already.has(record.id) || bound.has(record.id)
                          ? intl.formatMessage({
                              id: 'pages.instances.selected',
                              defaultMessage: 'Selected',
                            })
                          : intl.formatMessage({
                              id: 'pages.instances.select',
                              defaultMessage: 'Select',
                            })}
                      </Button>
                    ),
                  },
                ]}
              />
            </Modal>
          );
        })()}
    </>
  );
}
