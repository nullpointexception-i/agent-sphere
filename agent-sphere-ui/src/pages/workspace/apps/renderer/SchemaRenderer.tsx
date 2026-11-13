import { Empty, Table, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { SCHEMA_DATA_SOURCE } from '@/constants/application';
import { applicationApi } from '@/services/agentSphere/application';
import { tw } from '../../i18n';

interface SchemaNode {
  component?: string;
  props?: Record<string, any>;
  children?: SchemaNode[];
}

interface SchemaDataSource {
  type?: string;
  applicationId?: number;
  sceneId?: number;
  pageSize?: number;
}

const ALLOWED = new Set([
  'PageHeader',
  'Section',
  'StatCard',
  'DataTable',
  'EmptyState',
  'Markdown',
  'Tag',
  'Divider',
]);

export function SchemaRenderer({ schema }: { schema?: SchemaNode | null }) {
  if (!schema?.component) {
    return <Empty description={tw('apps.comingSoon', 'No schema')} />;
  }
  return <Node node={schema} />;
}

function Node({ node }: { node: SchemaNode }): ReactNode {
  const dataSource: SchemaDataSource | undefined = node.props?.dataSource;
  const [rows, setRows] = useState<any[]>(node.props?.rows || []);
  useEffect(() => {
    if (
      dataSource?.type !== SCHEMA_DATA_SOURCE.APPLICATION_RUNS ||
      !dataSource.applicationId
    ) {
      return;
    }
    applicationApi
      .runs({
        applicationId: dataSource.applicationId,
        sceneId: dataSource.sceneId,
        page: 1,
        pageSize: dataSource.pageSize || 20,
      })
      .then((page) => setRows(page.records || []))
      .catch(() => setRows([]));
  }, [
    dataSource?.type,
    dataSource?.applicationId,
    dataSource?.sceneId,
    dataSource?.pageSize,
  ]);
  const component = node.component || '';
  if (!ALLOWED.has(component)) {
    return (
      <div style={{ color: '#b37b00', fontSize: 12 }}>
        {tw('apps.unsupportedComponent', 'Unsupported component: {component}', {
          component,
        })}
      </div>
    );
  }
  const props = node.props || {};
  const children = node.children?.map((child) => (
    <Node
      key={String(
        child.props?.key ?? child.props?.id ?? child.component ?? 'node',
      )}
      node={child}
    />
  ));

  switch (component) {
    case 'PageHeader':
      return (
        <div style={{ marginBottom: 16 }}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            {props.title}
          </Typography.Title>
          {props.description ? (
            <Typography.Text type="secondary">
              {props.description}
            </Typography.Text>
          ) : null}
        </div>
      );
    case 'Section':
      return (
        <section style={{ marginBottom: 16 }}>
          {props.title ? (
            <Typography.Title level={5}>{props.title}</Typography.Title>
          ) : null}
          {children}
        </section>
      );
    case 'StatCard':
      return (
        <div
          style={{
            display: 'inline-block',
            minWidth: 140,
            margin: '0 12px 12px 0',
            padding: 14,
            border: '1px solid #efedf7',
            borderRadius: 10,
            background: '#fff',
          }}
        >
          <div style={{ color: '#8b86a0', fontSize: 12 }}>{props.label}</div>
          <div style={{ color: '#241b4d', fontSize: 20, marginTop: 4 }}>
            {props.value}
          </div>
        </div>
      );
    case 'DataTable':
      return (
        <Table
          size="small"
          pagination={false}
          rowKey={props.rowKey || 'id'}
          columns={(props.columns || []).map((column: any) => ({
            title: column.title,
            dataIndex: column.dataIndex,
            key: column.key || column.dataIndex,
          }))}
          dataSource={rows}
        />
      );
    case 'EmptyState':
      return <Empty description={props.description} />;
    case 'Markdown':
      return <Typography.Paragraph>{props.text}</Typography.Paragraph>;
    case 'Tag':
      return <Tag>{props.text}</Tag>;
    case 'Divider':
      return <hr style={{ border: 0, borderTop: '1px solid #efedf7' }} />;
    default:
      return children;
  }
}
