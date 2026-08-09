import { PageContainer } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Empty } from 'antd';

export default function PeriodicTasks() {
  const intl = useIntl();
  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <div style={{ paddingTop: 140 }}>
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={intl.formatMessage({
            id: 'pages.periodicTasks.comingSoon',
            defaultMessage: '敬请期待',
          })}
        />
      </div>
    </PageContainer>
  );
}
