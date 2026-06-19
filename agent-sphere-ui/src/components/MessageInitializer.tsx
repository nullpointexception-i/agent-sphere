import { useEffect } from 'react';
import { App } from 'antd';
import { setMessageApi } from '@/utils/globalMessage';

export default function MessageInitializer() {
  const { message } = App.useApp();
  useEffect(() => { setMessageApi(message); }, [message]);
  return null;
}
