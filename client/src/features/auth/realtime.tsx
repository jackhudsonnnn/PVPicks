import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import type { ReactNode } from "react";
import { RealtimeConnection, type Invalidation, type RealtimeStatus } from "./realtime-connection";

type Listener = (event: Invalidation) => void;

const RealtimeContext = createContext<RealtimeConnection | null>(null);

/** Opens one realtime connection for the signed-in user and closes it on sign-out. */
export function RealtimeProvider({
  onConnected,
  onClosed,
  children,
}: {
  onConnected: () => void;
  onClosed: () => void;
  children: ReactNode;
}) {
  const callbacks = useRef({ onConnected, onClosed });
  callbacks.current = { onConnected, onClosed };
  const [connection] = useState(
    () =>
      new RealtimeConnection({
        onConnected: () => callbacks.current.onConnected(),
        onClosed: () => callbacks.current.onClosed(),
      }),
  );
  useEffect(() => {
    connection.start();
    return () => connection.stop();
  }, [connection]);
  return <RealtimeContext.Provider value={connection}>{children}</RealtimeContext.Provider>;
}

/** Calls `listener` for each invalidation on `destination`; does nothing outside a RealtimeProvider. */
export function useRealtimeFeed(destination: string, listener: Listener) {
  const connection = useContext(RealtimeContext);
  const latest = useRef(listener);
  latest.current = listener;
  useEffect(() => {
    if (!connection) return;
    return connection.listen(destination, (event) => latest.current(event));
  }, [connection, destination]);
}

const noStatus = () => () => {};

export function useRealtimeStatus(): RealtimeStatus | null {
  const connection = useContext(RealtimeContext);
  return useSyncExternalStore(
    connection ? (listener) => connection.onStatus(listener) : noStatus,
    () => connection?.status ?? null,
    () => null,
  );
}
