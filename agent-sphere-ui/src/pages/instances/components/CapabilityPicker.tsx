import { ClearOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { Button, DatePicker, Input, Table } from 'antd';
import type dayjs from 'dayjs';
import { useEffect, useState } from 'react';
import { formatParamDate, formatTime } from '@/utils/format';

interface Props {
  fetchFn: (params: any) => Promise<any>;
  boundIds: Set<number>;
  addedIds: Set<number>;
  onSelect: (item: any) => void;
  onDeselect: (item: any) => void;
}

export default function CapabilityPicker({
  fetchFn,
  boundIds,
  addedIds,
  onSelect,
  onDeselect,
}: Props) {
  const intl = useIntl();
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [timeRange, setTimeRange] = useState<
    [dayjs.Dayjs | null, dayjs.Dayjs | null]
  >([null, null]);

  const load = (p: number, ps: number, kw: string) => {
    fetchFn({
      keyword: kw || undefined,
      startTime: timeRange[0] ? formatParamDate(timeRange[0]) : undefined,
      endTime: timeRange[1]
        ? formatParamDate(timeRange[1].endOf('day'))
        : undefined,
      page: p,
      size: ps,
    })
      .then((r: any) => {
        setData(r.records || r);
        setTotal(r.total ?? 0);
      })
      .catch(() => setData([]));
  };

  useEffect(() => {
    load(page, pageSize, keyword);
  }, [page, pageSize]);

  const isDisabled = (id: number) => boundIds.has(id) || addedIds.has(id);

  return (
    <div>
      <div
        style={{
          marginBottom: 12,
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        <Input.Search
          placeholder={intl.formatMessage({ id: 'pages.search.placeholder' })}
          style={{ width: 200 }}
          onSearch={(v) => {
            setKeyword(v);
            setPage(1);
            load(1, pageSize, v);
          }}
          allowClear
          onClear={() => {
            setKeyword('');
            setPage(1);
            load(1, pageSize, '');
          }}
          maxLength={255}
        />
        <DatePicker.RangePicker
          value={
            timeRange[0] && timeRange[1]
              ? (timeRange as [dayjs.Dayjs, dayjs.Dayjs])
              : undefined
          }
          onChange={(dates) => {
            const next = (dates || [null, null]) as [
              dayjs.Dayjs | null,
              dayjs.Dayjs | null,
            ];
            setTimeRange(next);
            setPage(1);
            fetchFn({
              keyword: keyword || undefined,
              startTime: next[0] ? formatParamDate(next[0]) : undefined,
              endTime: next[1]
                ? formatParamDate(next[1].endOf('day'))
                : undefined,
              page: 1,
              size: pageSize,
            })
              .then((r: any) => {
                setData(r.records || r);
                setTotal(r.total ?? 0);
              })
              .catch(() => setData([]));
          }}
        />
        <Button
          icon={<ClearOutlined />}
          onClick={() => {
            setKeyword('');
            setTimeRange([null, null]);
            setPage(1);
            load(1, pageSize, '');
          }}
        />
      </div>
      <Table
        rowKey="id"
        dataSource={data}
        size="small"
        pagination={{
          current: page,
          total,
          pageSize,
          showSizeChanger: true,
          pageSizeOptions: [5, 10, 20, 50],
          onChange: (p, ps) => {
            setPage(p);
            setPageSize(ps);
          },
        }}
        locale={{ emptyText: intl.formatMessage({ id: 'pages.table.empty' }) }}
        columns={[
          {
            title: intl.formatMessage({ id: 'pages.table.id' }),
            dataIndex: 'id',
            width: 60,
          },
          {
            title: intl.formatMessage({ id: 'pages.table.name' }),
            dataIndex: 'name',
            ellipsis: true,
          },
          {
            title: intl.formatMessage({ id: 'pages.table.description' }),
            dataIndex: 'description',
            ellipsis: true,
          },
          {
            title: intl.formatMessage({ id: 'pages.table.created' }),
            dataIndex: 'createdAt',
            width: 160,
            render: (v: any) => formatTime(v),
          },
          {
            title: '',
            width: 90,
            render: (_: any, record: any) => (
              <Button
                size="small"
                disabled={isDisabled(record.id)}
                type={addedIds.has(record.id) ? 'primary' : 'default'}
                onClick={() =>
                  addedIds.has(record.id)
                    ? onDeselect(record)
                    : onSelect(record)
                }
              >
                {isDisabled(record.id)
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
    </div>
  );
}
