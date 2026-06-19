import { PageContainer } from '@ant-design/pro-components';
import { history, useIntl } from '@umijs/max';
import { Card, Col, Row, Statistic } from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

export default function Dashboard() {
  const intl = useIntl();
  const [providerCount, setProviderCount] = useState(0);

  useEffect(() => {
    agentApi.modelProviders.count().then(setProviderCount).catch(() => {});
  }, []);

  return (
    <PageContainer title={false}>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} md={6}>
          <Card hoverable onClick={() => history.push('/chat')}>
            <Statistic
              title={intl.formatMessage({ id: 'pages.dashboard.chat.title', defaultMessage: 'Chat' })}
              value={intl.formatMessage({ id: 'pages.dashboard.chat.desc', defaultMessage: 'Start conversation' })}
              styles={{ content: { fontSize: 14 } }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card hoverable onClick={() => history.push('/instances')}>
            <Statistic
              title={intl.formatMessage({ id: 'pages.dashboard.instances.title', defaultMessage: 'Instances' })}
              value={intl.formatMessage({ id: 'pages.dashboard.instances.desc', defaultMessage: 'Manage agents' })}
              styles={{ content: { fontSize: 14 } }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card hoverable onClick={() => history.push('/models')}>
            <Statistic
              title={intl.formatMessage({ id: 'pages.dashboard.models.title', defaultMessage: 'Model Providers' })}
              value={providerCount}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card hoverable onClick={() => history.push('/capabilities/mcp')}>
            <Statistic
              title={intl.formatMessage({ id: 'pages.dashboard.capabilities.title', defaultMessage: 'Capabilities' })}
              value={intl.formatMessage({ id: 'pages.dashboard.capabilities.desc', defaultMessage: 'MCP / Skill / CLI' })}
              styles={{ content: { fontSize: 14 } }}
            />
          </Card>
        </Col>
      </Row>
    </PageContainer>
  );
}
