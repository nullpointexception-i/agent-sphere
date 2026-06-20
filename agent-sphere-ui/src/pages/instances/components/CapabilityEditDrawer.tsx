import { App, Button, Drawer, Modal, Table, Tabs, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import CapabilityPicker from './CapabilityPicker';

interface Props {
  record: any;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export default function CapabilityEditDrawer({ record, open, onClose, onSaved }: Props) {
  const { message } = App.useApp();
  const intl = useIntl();
  const locale = intl.locale;
  const builtinDisplay = (tool: any) =>
    locale === 'en-US' ? (tool?.displayNameEn || tool?.name || '') : (tool?.displayNameCn || tool?.name || '');
  const [boundCaps, setBoundCaps] = useState<any[]>([]);
  const [addCaps, setAddCaps] = useState<any[]>([]);
  const [addingType, setAddingType] = useState<string | null>(null);
  const [allBuiltin, setAllBuiltin] = useState<any[]>([]);
  const [toRemoveIds, setToRemoveIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (!open) return;
    setAddCaps([]);
    setToRemoveIds(new Set());
    setAddingType(null);
    agentApi.instanceCapabilities.listFull(record.id).then(setBoundCaps).catch(() => setBoundCaps([]));
    agentApi.builtin.list().then(setAllBuiltin).catch(() => setAllBuiltin([]));
  }, [open, record]);

  const save = async () => {
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

  const markRemove = (id: number) => setToRemoveIds((prev) => new Set(prev).add(id));
  const unmarkRemove = (id: number) => setToRemoveIds((prev) => { const next = new Set(prev); next.delete(id); return next; });

  const toggleBuiltinPick = (id: number) => {
    if (addCaps.some((c) => c.capabilityType === 'builtin' && c.id === id)) {
      setAddCaps((prev) => prev.filter((c) => !(c.capabilityType === 'builtin' && c.id === id)));
    } else {
      setAddCaps((prev) => [...prev, { id, capabilityType: 'builtin' }]);
    }
  };

  const bindName = (cap: any) => {
    if (cap.capabilityType === 'builtin') return builtinName(cap.capabilityId ?? cap.id);
    if (cap.name) return cap.name;
    return `#${cap.capabilityId}`;
  };

  const selectedCaps = (type: string) => {
    const ids = new Set(addCaps.filter((c) => c.capabilityType === type).map((c) => c.id));
    const boundIds = new Set(boundCaps.filter((c) => c.capabilityType === type).map((c) => c.capabilityId));
    return { ids, boundIds };
  };

  return (
    <>
      <Drawer
        title={record?.name}
        open={open}
        onClose={onClose}
        size="large"
        extra={
          <Button type="primary" onClick={save}>
            {intl.formatMessage({ id: 'pages.save', defaultMessage: 'Save' })}
          </Button>
        }
      >
        <div style={{ marginBottom: 12, padding: '8px 12px', background: '#fff7e6', borderRadius: 6, fontSize: 13, color: '#d46b08' }}>
          {intl.formatMessage({ id: 'pages.instances.capabilitiesSaveHint', defaultMessage: 'Changes take effect only after clicking Save.' })}
        </div>
        <Tabs
          tabPlacement="left"
          items={capTypes.map((type) => {
            const bound = boundCaps.filter((c) => c.capabilityType === type);
            const added = addCaps.filter((c) => c.capabilityType === type);
            const autoBuiltinIds = new Set(allBuiltin.filter(b => b.needConfig === false).map(b => b.id));
            const autoRows = type === 'builtin' ? allBuiltin.filter(b => b.needConfig === false).map(b => ({
              id: `auto-${b.id}`,
              capabilityId: b.id,
              capabilityType: 'builtin',
              name: b.name,
              displayNameCn: b.displayNameCn,
              displayNameEn: b.displayNameEn,
              _auto: true,
            })) : [];
            const userBound = type === 'builtin' ? bound.filter(b => !autoBuiltinIds.has(b.capabilityId)) : bound;
            return {
              key: type,
              label: capTypeLabel[type],
              children: (
                <div>
                  <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontWeight: 500 }}>
                      {intl.formatMessage({ id: 'pages.instances.currentCapabilities', defaultMessage: 'Current' })}
                    </span>
                    <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setAddingType(type)}>
                      {intl.formatMessage({ id: 'pages.instances.addCapability', defaultMessage: 'Add' })}
                    </Button>
                  </div>
                  <Table
                    rowKey={(r: any) => r._auto ? `auto-${r.capabilityId}` : r.capabilityId != null ? `${type}-${r.capabilityId}` : `add-${r.id}`}
                    dataSource={[...autoRows, ...userBound, ...added]}
                    size="small"
                    pagination={false}
                    locale={{ emptyText: intl.formatMessage({ id: 'pages.table.empty' }) }}
                    columns={[
                      { title: intl.formatMessage({ id: 'pages.table.name' }), dataIndex: 'name', ellipsis: true, render: (_: any, r: any) => {
                        if (r._auto) return <span style={{ fontStyle: 'italic', color: '#666' }}>{bindName(r)}</span>;
                        const removed = r.capabilityId != null && toRemoveIds.has(r.id);
                        return <span style={removed ? { textDecoration: 'line-through', color: '#999' } : undefined}>{bindName(r)}</span>;
                      }},
                      {
                        title: '',
                        width: 80,
                        render: (_: any, r: any) => r._auto ? (
                          <Tag style={{ fontSize: 10, lineHeight: '16px' }} color="blue">
                            {intl.formatMessage({ id: 'pages.instances.autoInclude', defaultMessage: 'Built-in' })}
                          </Tag>
                        ) : (
                          <Button
                            type="link"
                            danger={!toRemoveIds.has(r.id)}
                            size="small"
                            onClick={() => {
                              if (r.capabilityId != null && r.capabilityType) {
                                if (toRemoveIds.has(r.id)) unmarkRemove(r.id);
                                else markRemove(r.id);
                              } else {
                                setAddCaps((prev) => prev.filter((c) => !(c.capabilityType === type && c.id === r.id)));
                              }
                            }}
                          >
                            {toRemoveIds.has(r.id) && r.capabilityId != null
                              ? intl.formatMessage({ id: 'pages.instances.undoRemove', defaultMessage: 'Undo' })
                              : intl.formatMessage({ id: 'pages.instances.removeCapability', defaultMessage: 'Remove' })}
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
            onSelect={(item) => setAddCaps((prev) => [...prev, { id: item.id, name: item.name, capabilityType: addingType }])}
            onDeselect={(item) => setAddCaps((prev) => prev.filter((c) => !(c.capabilityType === addingType && c.id === item.id)))}
          />
        </Modal>
      )}
      {addingType === 'builtin' && (() => {
        const already = new Set(addCaps.filter((c) => c.capabilityType === 'builtin').map((c) => c.id));
        const bound = new Set(boundCaps.filter((c) => c.capabilityType === 'builtin').map((c) => c.capabilityId));
        return (
          <Modal
            title={`${intl.formatMessage({ id: 'pages.instances.addCapability', defaultMessage: 'Add' })} - ${capTypeLabel.builtin}`}
            open
            footer={null}
            onCancel={() => setAddingType(null)}
        size="large"
          >
            <Table
              rowKey="id"
              dataSource={allBuiltin.filter(b => b.needConfig !== false)}
              size="small"
              pagination={false}
              locale={{ emptyText: intl.formatMessage({ id: 'pages.table.empty' }) }}
              columns={[
                { title: intl.formatMessage({ id: 'pages.table.name' }), dataIndex: 'name', ellipsis: true, render: (_: any, record: any) => builtinDisplay(record) },
                { title: intl.formatMessage({ id: 'pages.table.description' }), dataIndex: 'description', ellipsis: true },
                {
                  title: '',
                  width: 90,
                  render: (_: any, record: any) => (
                    <Button
                      size="small"
                      disabled={bound.has(record.id) || already.has(record.id)}
                      type={already.has(record.id) ? 'primary' : 'default'}
                      onClick={() => toggleBuiltinPick(record.id)}
                    >
                      {already.has(record.id) || bound.has(record.id)
                        ? intl.formatMessage({ id: 'pages.instances.selected', defaultMessage: 'Selected' })
                        : intl.formatMessage({ id: 'pages.instances.select', defaultMessage: 'Select' })}
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
