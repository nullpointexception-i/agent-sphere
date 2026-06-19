import type { MessageInstance } from 'antd/es/message/interface';

let instance: MessageInstance | null = null;

export function setMessageApi(api: MessageInstance) {
  instance = api;
}

export function showError(msg: string) {
  if (instance) {
    instance.error(msg);
  } else {
    console.error(msg);
  }
}

export function showSuccess(msg: string) {
  if (instance) {
    instance.success(msg);
  } else {
    console.log(msg);
  }
}

export function showWarning(msg: string) {
  if (instance) {
    instance.warning(msg);
  } else {
    console.warn(msg);
  }
}
