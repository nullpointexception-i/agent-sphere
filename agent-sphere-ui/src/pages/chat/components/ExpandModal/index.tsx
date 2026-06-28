import { useIntl } from '@umijs/max';
import { Input, Modal } from 'antd';

interface ExpandModalProps {
  open: boolean;
  onClose: () => void;
  onOk: () => void;
  text: string;
  onTextChange: (v: string) => void;
}

export default function ExpandModal({
  open,
  onClose,
  onOk,
  text,
  onTextChange,
}: ExpandModalProps) {
  const intl = useIntl();

  return (
    <Modal
      title={intl.formatMessage({
        id: 'pages.chat.expandInput',
        defaultMessage: 'Expand Input',
      })}
      open={open}
      onOk={onOk}
      onCancel={onClose}
    >
      <Input.TextArea
        rows={8}
        value={text}
        onChange={(e) => onTextChange(e.target.value)}
        placeholder={intl.formatMessage({
          id: 'pages.chat.typeMessage',
          defaultMessage: 'Type a message...',
        })}
        maxLength={5000}
      />
    </Modal>
  );
}
