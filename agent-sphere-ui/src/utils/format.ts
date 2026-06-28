import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);

const FMT = 'YYYY-MM-DD HH:mm:ss';
const API_FMT = 'YYYY-MM-DDTHH:mm:ss';
const UTC8_OFFSET = 480; // minutes

export function formatTime(
  val: string | number | Date | undefined | null,
): string {
  if (!val) return '-';
  const d = dayjs(val);
  return d.isValid() ? d.format(FMT) : String(val);
}

export function formatParamDate(
  d: dayjs.Dayjs | null | undefined,
): string | undefined {
  if (!d) return undefined;
  return d.utc().utcOffset(UTC8_OFFSET).format(API_FMT);
}

export function nowUTC8(): dayjs.Dayjs {
  return dayjs().utc().utcOffset(UTC8_OFFSET);
}
