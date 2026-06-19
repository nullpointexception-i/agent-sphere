import { App, Card, Drawer, Empty, Pagination, Spin } from 'antd';
import { useIntl } from '@umijs/max';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

interface InstanceDrawerProps {
  open: boolean;
  onClose: () => void;
  onSelect: (instance: any) => void;
  selectedId: string;
}

const PAGE_SIZE = 6;

export default function InstanceDrawer({ open, onClose, onSelect, selectedId }: InstanceDrawerProps) {
  const { message } = App.useApp();
  const intl = useIntl();
  const [instances, setInstances] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    agentApi.instances.list({ page, size: PAGE_SIZE })
      .then((res: any) => {
        setInstances(res.records || []);
        setTotal(res.total || 0);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [open, page]);

  return (
    <Drawer
      title={intl.formatMessage({ id: 'pages.landing.selectInstance' })}
      placement="right"
      size="large"
      open={open}
      onClose={onClose}
    >
      <Spin spinning={loading}>
        {instances.length === 0 ? (
          <Empty description={intl.formatMessage({ id: 'pages.table.empty' })} />
        ) : (
          <div>
            {instances.map((inst: any) => {
              const isSelected = String(inst.id) === selectedId;
              return (
                <Card
                  key={inst.id}
                  size="small"
                  hoverable
                  style={{
                    marginBottom: 12,
                    borderColor: isSelected ? '#1677ff' : undefined,
                    background: isSelected ? '#e6f4ff' : undefined,
                  }}
                  onClick={() => {
                    if (!inst.modelRouteId) {
                      message.warning(intl.formatMessage({ id: 'pages.landing.noModelRoute', defaultMessage: '请先为该实例配置默认模型路由' }));
                      return;
                    }
                    onSelect(inst);
                  }}
                >
                  <Card.Meta
                    title={inst.name}
                    description={inst.description || intl.formatMessage({ id: 'pages.table.empty' })}
                  />
                </Card>
              );
            })}
          </div>
        )}
      </Spin>
      {total > PAGE_SIZE && (
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <Pagination
            current={page}
            total={total}
            pageSize={PAGE_SIZE}
            onChange={(p) => setPage(p)}
            size="small"
          />
        </div>
      )}
    </Drawer>
  );
}
