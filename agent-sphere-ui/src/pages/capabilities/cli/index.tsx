import {
  ClearOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { App, Button, DatePicker, Form, Input, Modal } from 'antd';
import type dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';

export default function CliList() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [timeRange, setTimeRange] = useState<
    [dayjs.Dayjs | null, dayjs.Dayjs | null]
  >([null, null]);
  const [tableScrollY, setTableScrollY] = useState(400);
  const [selectedRowKeys, setSelectedRowKeys] = useState<number[]>([]);
  const [viewRecord, setViewRecord] = useState<any>(null);

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  const columns = [
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
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: any) => formatTime(v),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 160,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setViewRecord(record)}
          />
          <Can code="capability:cli:update">
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setEditing(record);
                form.setFieldsValue(record);
                setModalOpen(true);
              }}
            />
          </Can>
          <Can code="capability:cli:delete">
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
                    { name: 'CLI' },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: 'CLI' },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.cli.delete(record.id);
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
      if (editing) {
        await agentApi.cli.update(editing.id, values);
        message.success('Updated');
      } else {
        await agentApi.cli.create(values);
        message.success('Created');
      }
      setModalOpen(false);
      setEditing(null);
      form.resetFields();
      actionRef.current?.reload();
    } finally {
      setSubmitting(false);
    }
  };

  const handleBatchDelete = () => {
    if (selectedRowKeys.length === 0) return;
    modal.confirm({
      title: intl.formatMessage(
        { id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' },
        { name: `${selectedRowKeys.length} CLI tools` },
      ),
      content: intl.formatMessage(
        {
          id: 'pages.deleteConfirm.content',
          defaultMessage: 'Are you sure you want to delete this {name}?',
        },
        { name: 'CLI tool' },
      ),
      okType: 'danger',
      onOk: async () => {
        await agentApi.cli.batchDelete(selectedRowKeys);
        message.success(
          intl.formatMessage(
            {
              id: 'pages.batchDelete.success',
              defaultMessage: 'Deleted {count} items',
            },
            { count: selectedRowKeys.length },
          ),
        );
        setSelectedRowKeys([]);
        actionRef.current?.reload();
      },
    });
  };

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <ProTable
        actionRef={actionRef}
        rowKey="id"
        search={false}
        options={false}
        scroll={{ y: tableScrollY }}
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
          const res = await agentApi.cli.list({
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
        rowSelection={{
          selectedRowKeys,
          onChange: (keys: any) => setSelectedRowKeys(keys),
        }}
        toolBarRender={() => [
          selectedRowKeys.length > 0 && (
            <Button
              key="batchDelete"
              danger
              icon={<DeleteOutlined />}
              onClick={handleBatchDelete}
            >
              {intl.formatMessage(
                { id: 'pages.batchDelete', defaultMessage: 'Delete ({count})' },
                { count: selectedRowKeys.length },
              )}
            </Button>
          ),
          <Input.Search
            key="search"
            placeholder={intl.formatMessage({ id: 'pages.search.placeholder' })}
            style={{ width: 200 }}
            onSearch={(value) => {
              setKeyword(value);
            }}
            allowClear
            onClear={() => setKeyword('')}
            maxLength={255}
          />,
          <DatePicker.RangePicker
            key="date"
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
          />,
          <Button
            key="clear"
            icon={<ClearOutlined />}
            onClick={() => {
              setKeyword('');
              setTimeRange([null, null]);
              actionRef.current?.reload();
            }}
          />,
          <Can key="new" code="capability:cli:create">
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditing(null);
                form.resetFields();
                setModalOpen(true);
              }}
            />
          </Can>,
        ]}
      />
      <Modal
        title={
          editing
            ? intl.formatMessage({
                id: 'pages.modal.editCli',
                defaultMessage: 'Edit CLI',
              })
            : intl.formatMessage({
                id: 'pages.modal.newCli',
                defaultMessage: 'New CLI',
              })
        }
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => {
          setModalOpen(false);
          setEditing(null);
        }}
        confirmLoading={submitting}
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
            name="commandTemplate"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.capabilities.commandTemplate',
                defaultMessage: 'Command Template',
              }),
              intl.formatMessage({ id: 'pages.hint.url' }),
            )}
            rules={[{ required: true }]}
          >
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
          <Form.Item
            name="paramSchema"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.capabilities.paramSchema',
                defaultMessage: 'Parameter Schema',
              }),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
          >
            <Input.TextArea rows={6} maxLength={5000} />
          </Form.Item>
          <Form.Item
            name="workingDir"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.capabilities.workingDir',
                defaultMessage: 'Working Directory',
              }),
              intl.formatMessage({ id: 'pages.hint.url' }),
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={viewRecord?.name}
        open={!!viewRecord}
        onCancel={() => setViewRecord(null)}
        footer={null}
        width={560}
      >
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {[
              {
                label: intl.formatMessage({ id: 'pages.table.id' }),
                value: viewRecord?.id,
              },
              {
                label: intl.formatMessage({ id: 'pages.table.name' }),
                value: viewRecord?.name,
              },
              {
                label: intl.formatMessage({ id: 'pages.table.description' }),
                value: viewRecord?.description || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.createdBy',
                  defaultMessage: 'Created By',
                }),
                value: viewRecord?.createdBy || '-',
              },
              {
                label: intl.formatMessage({ id: 'pages.table.created' }),
                value: formatTime(viewRecord?.createdAt),
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedBy',
                  defaultMessage: 'Updated By',
                }),
                value: viewRecord?.updatedBy || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedAt',
                  defaultMessage: 'Updated At',
                }),
                value: formatTime(viewRecord?.updatedAt),
              },
            ].map((row) => (
              <tr key={row.label}>
                <td
                  style={{
                    padding: '8px 12px',
                    fontWeight: 500,
                    color: '#8c8c8c',
                    borderBottom: '1px solid #f0f0f0',
                    width: 120,
                    verticalAlign: 'top',
                  }}
                >
                  {row.label}
                </td>
                <td
                  style={{
                    padding: '8px 12px',
                    borderBottom: '1px solid #f0f0f0',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                  }}
                >
                  {row.value || '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Modal>
    </PageContainer>
  );
}
