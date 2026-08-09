import {
  ApiOutlined,
  AppstoreOutlined,
  CheckSquareOutlined,
  ClearOutlined,
  CloudOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  UnorderedListOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, useLocation } from '@umijs/max';
import {
  App,
  Avatar,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Form,
  Input,
  Modal,
  Pagination,
  Select,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Upload,
} from 'antd';
import type dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import SetModelRouteModal from '@/components/SetModelRouteModal';
import { useCan } from '@/hooks/usePermission';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';
import CapabilityEditDrawer from './components/CapabilityEditDrawer';
import InfoEditDrawer from './components/InfoEditDrawer';
import { useStyles } from './style';

export default function InstanceList() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const { styles } = useStyles();
  const canEdit = useCan('instance:update');
  const canManageCaps = useCan('instance:capability:bind');
  const canBindModel = useCan('instance:bind-model');
  const canDelete = useCan('instance:delete');
  const actionRef = useRef<any>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [infoDrawerOpen, setInfoDrawerOpen] = useState(false);
  const [capDrawerOpen, setCapDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [viewing, setViewing] = useState<any>(null);
  const [modelRouteModal, setModelRouteModal] = useState(false);
  const [modelRouteInstance, setModelRouteInstance] = useState<any>(null);
  const [, setProviders] = useState<any[]>([]);
  const [allRoutes, setAllRoutes] = useState<any[]>([]);
  const [capabilities, setCapabilities] = useState<any[]>([]);
  const [detailTab, setDetailTab] = useState('info');
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [timeRange, setTimeRange] = useState<
    [dayjs.Dayjs | null, dayjs.Dayjs | null]
  >([null, null]);
  const [viewMode, setViewMode] = useState<'card' | 'table'>('card');
  const [listData, setListData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [imagePreview, setImagePreview] = useState('');
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [refreshKey, setRefreshKey] = useState(0);
  const [tableScrollY, setTableScrollY] = useState(400);
  const routeNameMap = useMemo(() => {
    const m: Record<number, string> = {};
    allRoutes.forEach((r: any) => {
      m[r.id] = r.modelName;
    });
    return m;
  }, [allRoutes]);

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  const fetchData = useCallback(
    async (p: number, ps?: number) => {
      const size = ps ?? 12;
      const res = await agentApi.instances.list({
        keyword: keyword || undefined,
        startTime: formatParamDate(timeRange[0]),
        endTime: formatParamDate(timeRange[1]?.endOf('day')),
        page: p,
        size,
      });
      setListData(res.records || res);
      setTotal(res.total ?? 0);
      setPage(p);
      return res;
    },
    [keyword, timeRange],
  );

  useEffect(() => {
    fetchData(1);
  }, [fetchData, refreshKey]);

  useEffect(() => {
    agentApi.modelProviders
      .list()
      .then(setProviders)
      .catch(() => {});
    agentApi.routes
      .listAll()
      .then(setAllRoutes)
      .catch(() => {});
  }, []);

  const location = useLocation();
  useEffect(() => {
    if ((location.state as any)?.openCreate) {
      setEditing(null);
      form.resetFields();
      setImagePreview('');
      setModalOpen(true);
      window.history.replaceState({}, '');
    }
  }, []);

  const columns: any[] = [
    {
      title: intl.formatMessage({ id: 'pages.table.id' }),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.name' }),
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.instances.businessType' }),
      dataIndex: 'businessType',
      key: 'businessType',
      width: 120,
      render: (v: any) =>
        v ? (
          <Tag>{v}</Tag>
        ) : (
          <span style={{ color: 'rgba(0,0,0,0.25)' }}>-</span>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: any) => formatTime(v),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 200,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={async () => {
              setViewing(record);
              setDetailTab('info');
              try {
                const caps = await agentApi.instanceCapabilities.listFull(
                  record.id,
                );
                setCapabilities(caps);
              } catch {
                setCapabilities([]);
              }
              setDetailOpen(true);
            }}
          />
          <Can code="instance:update">
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setEditing(record);
                setInfoDrawerOpen(true);
              }}
            />
          </Can>
          <Button
            type="link"
            size="small"
            icon={<ApiOutlined />}
            onClick={() => {
              setEditing(record);
              setCapDrawerOpen(true);
            }}
          />
          <Can code="instance:delete">
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              onClick={() => {
                modal.confirm({
                  title: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.title',
                      defaultMessage: 'Delete {name}',
                    },
                    { name: 'instance' },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: 'instance' },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.instances.delete(record.id);
                    message.success('Deleted');
                    actionRef.current?.reload();
                  },
                });
              }}
            />
          </Can>
        </>
      ),
    },
  ];

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const values = await form.validateFields();
      const payload = { ...values, image: imagePreview || undefined };
      if (editing) {
        await agentApi.instances.update(editing.id, payload);
        message.success('Updated');
      } else {
        await agentApi.instances.create(payload);
        message.success('Created');
      }
      setModalOpen(false);
      setEditing(null);
      setImagePreview('');
      form.resetFields();
      fetchData(1);
    } finally {
      setSubmitting(false);
    }
  };

  const capsColumns = [
    {
      title: intl.formatMessage({ id: 'pages.table.type' }),
      dataIndex: 'capabilityType',
      key: 'capabilityType',
    },
    {
      title: intl.formatMessage({ id: 'pages.table.name' }),
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      render: (v: any, record: any) => {
        if (record.capabilityType === 'builtin') {
          const display =
            intl.locale === 'en-US'
              ? record.displayNameEn || v
              : record.displayNameCn || v;
          return display ? <Tooltip title={display}>{display}</Tooltip> : '-';
        }
        return v ? <Tooltip title={v}>{v}</Tooltip> : '-';
      },
    },
  ];

  return (
    <PageContainer
      title={false}
      childrenContentStyle={{
        height: 'calc(100vh - 120px)',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        padding: 0,
      }}
    >
      <div
        style={{
          display: 'flex',
          gap: 8,
          padding: '16px 24px 0',
          flexWrap: 'wrap',
          alignItems: 'center',
          flexShrink: 0,
        }}
      >
        {selectMode ? (
          <>
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              disabled={selectedIds.size === 0}
              onClick={() => {
                modal.confirm({
                  title: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.title',
                      defaultMessage: 'Delete {name}',
                    },
                    { name: `${selectedIds.size} instances` },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: 'instance' },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.instances.batchDelete(
                      Array.from(selectedIds),
                    );
                    setSelectMode(false);
                    setSelectedIds(new Set());
                    message.success('Deleted');
                    fetchData(1);
                  },
                });
              }}
            >
              {intl.formatMessage({
                id: 'pages.chat.deleteSelected',
                defaultMessage: 'Delete',
              })}{' '}
              ({selectedIds.size})
            </Button>
            <Button
              size="small"
              onClick={() => {
                setSelectMode(false);
                setSelectedIds(new Set());
              }}
            >
              {intl.formatMessage({
                id: 'pages.chat.cancel',
                defaultMessage: 'Cancel',
              })}
            </Button>
          </>
        ) : (
          <>
            <Input.Search
              placeholder={intl.formatMessage({
                id: 'pages.search.placeholder',
              })}
              style={{ width: 200 }}
              onSearch={(value) => {
                setKeyword(value);
              }}
              allowClear
              onClear={() => setKeyword('')}
              maxLength={255}
            />
            <DatePicker.RangePicker
              value={
                timeRange[0] && timeRange[1]
                  ? (timeRange as [dayjs.Dayjs, dayjs.Dayjs])
                  : undefined
              }
              onChange={(dates) => {
                if (dates && dates[0] && dates[1]) {
                  const diff = dates[1].diff(dates[0], 'day');
                  if (diff > 90) {
                    setTimeRange([dates[0], dates[0].add(90, 'day')]);
                    message.warning(
                      intl.formatMessage({ id: 'pages.dateRange.warning' }),
                    );
                    return;
                  }
                }
                setTimeRange(dates || [null, null]);
              }}
            />
            <Button
              icon={<ClearOutlined />}
              onClick={() => {
                setKeyword('');
                setTimeRange([null, null]);
                setTimeout(() => setRefreshKey((k) => k + 1), 0);
              }}
            />
            <Tooltip
              title={intl.formatMessage({
                id: 'pages.instances.batchOperation',
                defaultMessage: 'Batch operation',
              })}
            >
              <Button
                icon={<CheckSquareOutlined />}
                onClick={() => setSelectMode(true)}
              />
            </Tooltip>
            <Button
              icon={
                viewMode === 'card' ? (
                  <UnorderedListOutlined />
                ) : (
                  <AppstoreOutlined />
                )
              }
              onClick={() =>
                setViewMode(viewMode === 'card' ? 'table' : 'card')
              }
            />
            <Can code="instance:create">
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => {
                  setEditing(null);
                  form.resetFields();
                  setImagePreview('');
                  setModalOpen(true);
                }}
              />
            </Can>
          </>
        )}
      </div>
      {viewMode === 'card' ? (
        <div
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
            minHeight: 0,
          }}
        >
          <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
            <div className={styles.cardGrid}>
              {listData.map((item: any) => (
                <Card
                  key={item.id}
                  className={styles.card}
                  size="small"
                  style={
                    selectMode && selectedIds.has(item.id)
                      ? { boxShadow: '0 0 0 2px #1677ff' }
                      : undefined
                  }
                  onClick={
                    selectMode
                      ? () => {
                          setSelectedIds((prev) => {
                            const next = new Set(prev);
                            if (next.has(item.id)) next.delete(item.id);
                            else next.add(item.id);
                            return next;
                          });
                        }
                      : undefined
                  }
                  actions={[
                    <div
                      key="view"
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        gap: 2,
                      }}
                      onClick={async () => {
                        setViewing(item);
                        setDetailTab('info');
                        try {
                          setCapabilities(
                            await agentApi.instanceCapabilities.listFull(
                              item.id,
                            ),
                          );
                        } catch {
                          setCapabilities([]);
                        }
                        setDetailOpen(true);
                      }}
                    >
                      <EyeOutlined />
                      <span style={{ fontSize: 11 }}>
                        {intl.formatMessage({
                          id: 'pages.table.view',
                          defaultMessage: 'View',
                        })}
                      </span>
                    </div>,
                    ...(canEdit
                      ? [
                          <div
                            key="edit"
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              gap: 2,
                            }}
                            onClick={() => {
                              setEditing(item);
                              setInfoDrawerOpen(true);
                            }}
                          >
                            <EditOutlined />
                            <span style={{ fontSize: 11 }}>
                              {intl.formatMessage({
                                id: 'pages.table.edit',
                                defaultMessage: 'Edit',
                              })}
                            </span>
                          </div>,
                        ]
                      : []),
                    ...(canManageCaps
                      ? [
                          <div
                            key="caps"
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              gap: 2,
                            }}
                            onClick={() => {
                              setEditing(item);
                              setCapDrawerOpen(true);
                            }}
                          >
                            <ApiOutlined />
                            <span style={{ fontSize: 11 }}>
                              {intl.formatMessage({
                                id: 'pages.instances.capabilities',
                                defaultMessage: 'Capabilities',
                              })}
                            </span>
                          </div>,
                        ]
                      : []),
                    ...(canBindModel
                      ? [
                          <div
                            key="model"
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              gap: 2,
                            }}
                            onClick={() => {
                              setModelRouteInstance(item);
                              setModelRouteModal(true);
                            }}
                          >
                            <CloudOutlined />
                            <span style={{ fontSize: 11 }}>
                              {intl.formatMessage({
                                id: 'pages.instances.modelRoute',
                                defaultMessage: 'Model',
                              })}
                            </span>
                          </div>,
                        ]
                      : []),
                    ...(canDelete
                      ? [
                          <div
                            key="delete"
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              gap: 2,
                            }}
                            onClick={() => {
                              modal.confirm({
                                title: intl.formatMessage(
                                  {
                                    id: 'pages.deleteConfirm.title',
                                    defaultMessage: 'Delete {name}',
                                  },
                                  { name: 'instance' },
                                ),
                                content: intl.formatMessage(
                                  {
                                    id: 'pages.deleteConfirm.content',
                                    defaultMessage:
                                      'Are you sure you want to delete this {name}?',
                                  },
                                  { name: 'instance' },
                                ),
                                okType: 'danger',
                                onOk: async () => {
                                  await agentApi.instances.delete(item.id);
                                  message.success('Deleted');
                                  fetchData(1);
                                },
                              });
                            }}
                          >
                            <DeleteOutlined />
                            <span style={{ fontSize: 11 }}>
                              {intl.formatMessage({
                                id: 'pages.table.delete',
                                defaultMessage: 'Delete',
                              })}
                            </span>
                          </div>,
                        ]
                      : []),
                  ]}
                >
                  <Card.Meta
                    avatar={
                      item.image ? (
                        <Avatar size={48} shape="square" src={item.image} />
                      ) : undefined
                    }
                    title={
                      <div className={styles.cardTitle}>
                        {selectMode && (
                          <span
                            style={{
                              marginRight: 4,
                              fontSize: 16,
                              flexShrink: 0,
                            }}
                          >
                            {selectedIds.has(item.id) ? '✓' : '○'}
                          </span>
                        )}
                        <span className={styles.cardName}>{item.name}</span>
                        <Tag
                          color={
                            item.status === 'ENABLED' ? 'green' : 'default'
                          }
                        >
                          {item.status}
                        </Tag>
                        {item.businessType && (
                          <Tag color="blue">{item.businessType}</Tag>
                        )}
                      </div>
                    }
                    description={
                      <div className={styles.cardDesc}>
                        <div>{item.description || '-'}</div>
                        <div className={styles.cardTime}>
                          {formatTime(item.createdAt)}
                        </div>
                      </div>
                    }
                  />
                </Card>
              ))}
            </div>
          </div>
          <div
            style={{
              display: 'flex',
              justifyContent: 'flex-start',
              padding: '16px 24px',
            }}
          >
            <Pagination
              current={page}
              total={total}
              pageSize={12}
              showSizeChanger={false}
              showQuickJumper
              onChange={(p) => fetchData(p, 12)}
            />
          </div>
        </div>
      ) : (
        <ProTable
          actionRef={actionRef}
          rowKey="id"
          search={false}
          options={false}
          toolBarRender={false}
          scroll={{ y: tableScrollY }}
          rowSelection={
            selectMode
              ? {
                  selectedRowKeys: listData
                    .filter((x: any) => selectedIds.has(x.id))
                    .map((x: any) => x.id),
                  onSelect: (record: any, selected: boolean) => {
                    setSelectedIds((prev) => {
                      const next = new Set(prev);
                      if (selected) next.add(record.id);
                      else next.delete(record.id);
                      return next;
                    });
                  },
                  onSelectAll: (
                    selected: boolean,
                    _: any,
                    changeRows: any[],
                  ) => {
                    setSelectedIds((prev) => {
                      const next = new Set(prev);
                      for (const r of changeRows) {
                        if (selected) next.add(r.id);
                        else next.delete(r.id);
                      }
                      return next;
                    });
                  },
                }
              : undefined
          }
          pagination={{
            defaultPageSize: 10,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: [5, 10, 20, 50],
            style: { justifyContent: 'flex-start' },
          }}
          params={{
            keyword: keyword || undefined,
            startTime: formatParamDate(timeRange[0]),
            endTime: formatParamDate(timeRange[1]?.endOf('day')),
          }}
          request={async (p) => {
            const res = await agentApi.instances.list({
              keyword: p.keyword,
              startTime: p.startTime,
              endTime: p.endTime,
              page: p.current,
              size: p.pageSize,
            });
            return {
              data: res.records || res,
              total: res.total ?? 0,
              success: true,
            };
          }}
          columns={columns}
        />
      )}
      <Modal
        title={
          editing
            ? intl.formatMessage({
                id: 'pages.modal.editInstance',
                defaultMessage: 'Edit Instance',
              })
            : intl.formatMessage({
                id: 'pages.modal.newInstance',
                defaultMessage: 'New Instance',
              })
        }
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => {
          setModalOpen(false);
          setEditing(null);
        }}
        confirmLoading={submitting}
        width={560}
      >
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
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
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
              intl.formatMessage({ id: 'pages.instances.systemPrompt' }),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
          >
            <Input.TextArea rows={4} maxLength={5000} />
          </Form.Item>
          <Form.Item
            name="businessType"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.instances.businessType',
                defaultMessage: '业务域',
              }),
              intl.formatMessage({
                id: 'pages.instances.businessType.extra',
                defaultMessage: '任务按业务域匹配该实例，需与调用方一致',
              }),
            )}
          >
            <Input maxLength={64} placeholder="sourcing" />
          </Form.Item>
        </Form>
      </Modal>
      <SetModelRouteModal
        open={modelRouteModal}
        instance={modelRouteInstance}
        onClose={() => {
          setModelRouteModal(false);
          setModelRouteInstance(null);
        }}
        onSuccess={() => {
          fetchData(1);
        }}
      />
      <InfoEditDrawer
        record={editing}
        open={infoDrawerOpen}
        onClose={() => {
          setInfoDrawerOpen(false);
          setEditing(null);
        }}
        onSaved={() => fetchData(1)}
      />
      <CapabilityEditDrawer
        record={editing}
        open={capDrawerOpen}
        onClose={() => {
          setCapDrawerOpen(false);
          setEditing(null);
        }}
        onSaved={() => fetchData(1)}
      />
      <Modal
        title={viewing?.name}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
        width={600}
      >
        <Tabs
          activeKey={detailTab}
          onChange={setDetailTab}
          items={[
            {
              key: 'info',
              label: intl.formatMessage({
                id: 'pages.instances.tabInfo',
                defaultMessage: 'Info',
              }),
              children: (
                <div className={styles.detailGrid}>
                  <Descriptions column={1} size="small" bordered>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.instances.detailId',
                      })}
                    >
                      {viewing?.id}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.instances.detailName',
                      })}
                    >
                      {viewing?.name}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.instances.detailDescription',
                      })}
                    >
                      {viewing?.description || '-'}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.instances.detailStatus',
                      })}
                    >
                      {viewing?.status}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.instances.businessType',
                        defaultMessage: '业务域',
                      })}
                    >
                      {viewing?.businessType || '-'}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.table.createdBy',
                        defaultMessage: 'Created By',
                      })}
                    >
                      {viewing?.createdBy || '-'}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.instances.detailCreated',
                      })}
                    >
                      {formatTime(viewing?.createdAt)}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.table.updatedBy',
                        defaultMessage: 'Updated By',
                      })}
                    >
                      {viewing?.updatedBy || '-'}
                    </Descriptions.Item>
                    <Descriptions.Item
                      label={intl.formatMessage({
                        id: 'pages.table.updatedAt',
                        defaultMessage: 'Updated At',
                      })}
                    >
                      {formatTime(viewing?.updatedAt)}
                    </Descriptions.Item>
                  </Descriptions>
                  <div>
                    <strong>
                      {intl.formatMessage({
                        id: 'pages.instances.detailSystemPrompt',
                      })}
                    </strong>
                    <div className={styles.jsonBlock}>
                      {viewing?.systemPrompt || '-'}
                    </div>
                  </div>
                </div>
              ),
            },
            {
              key: 'capabilities',
              label: intl.formatMessage({
                id: 'pages.instances.tabCapabilities',
                defaultMessage: 'Capabilities',
              }),
              children: (
                <Table
                  rowKey="id"
                  dataSource={capabilities}
                  columns={capsColumns}
                  size="small"
                  pagination={false}
                  locale={{
                    emptyText: intl.formatMessage({
                      id: 'pages.instances.emptyCapabilities',
                    }),
                  }}
                />
              ),
            },
          ]}
        />
      </Modal>
    </PageContainer>
  );
}
