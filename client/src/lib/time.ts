let clock24h = false;

/** Display setting: new chat/ticket times use a 24-hour clock. */
export function setClock24h(on: boolean) {
  clock24h = on;
}

/** Clock time as shown on new chat lines and tickets ("7:42 PM" or "19:42"). */
export function timeLabel(date: Date | number = new Date()) {
  return new Date(date).toLocaleTimeString([], {
    hour: clock24h ? "2-digit" : "numeric",
    minute: "2-digit",
    hour12: !clock24h,
  });
}

/** The current time as a label; see `timeLabel`. */
export const nowLabel = () => timeLabel();

/** Date + time for logs and inboxes ("Nov 4, 7:42 PM"). */
export function dateTimeLabel(date: Date | number) {
  return new Date(date).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: clock24h ? "2-digit" : "numeric",
    minute: "2-digit",
    hour12: !clock24h,
  });
}
