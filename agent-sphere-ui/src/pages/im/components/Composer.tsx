import { SendOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Input, Select, Tag, Upload } from 'antd';

interface Props {
  text: string;
  onTextChange: (value: string) => void;
  onSend: () => void;
  sending: boolean;
  participants: any[];
  mentions: number[];
  onMentionsChange: (value: number[]) => void;
  replyToId?: number;
  onClearReply: () => void;
  onUpload: (file: File) => void;
  labels: {
    placeholder: string;
    mention: string;
    send: string;
    replying: string;
    attachment: string;
  };
}

export function Composer({
  text,
  onTextChange,
  onSend,
  sending,
  participants,
  mentions,
  onMentionsChange,
  replyToId,
  onClearReply,
  onUpload,
  labels,
}: Props) {
  return (
    <div style={{ marginTop: 12 }}>
      {replyToId ? (
        <div style={{ marginBottom: 6 }}>
          <Tag closable onClose={onClearReply}>
            {labels.replying} #{replyToId}
          </Tag>
        </div>
      ) : null}
      <Select
        mode="multiple"
        allowClear
        value={mentions}
        onChange={onMentionsChange}
        placeholder={labels.mention}
        style={{ width: '100%', marginBottom: 6 }}
        options={participants
          .filter((participant) => participant.participantType === 'AGENT')
          .map((participant) => ({
            value: participant.participantId,
            label:
              participant.displayName || `Agent #${participant.participantId}`,
          }))}
      />
      <Input.TextArea
        value={text}
        onChange={(event) => onTextChange(event.target.value)}
        placeholder={labels.placeholder}
        autoSize={{ minRows: 2, maxRows: 5 }}
        onPressEnter={(event) => {
          if (!event.shiftKey) {
            event.preventDefault();
            onSend();
          }
        }}
      />
      <div style={{ textAlign: 'right', marginTop: 8 }}>
        <Upload
          showUploadList={false}
          beforeUpload={(file) => {
            onUpload(file);
            return Upload.LIST_IGNORE;
          }}
          disabled={sending}
        >
          <Button icon={<UploadOutlined />} disabled={sending}>
            {labels.attachment}
          </Button>
        </Upload>
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={sending}
          onClick={onSend}
          style={{ marginLeft: 8 }}
        >
          {labels.send}
        </Button>
      </div>
    </div>
  );
}
