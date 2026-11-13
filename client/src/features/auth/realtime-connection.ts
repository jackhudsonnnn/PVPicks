import {
  Client,
  ReconnectionTimeMode,
  type StompConfig,
  type StompSubscription,
} from "@stomp/stompjs";
import { z } from "zod";
import { csrf } from "./backend-api";

/**
 * Realtime invalidations (backend/api/realtime.schema.json). They only say what changed; views refetch the
 * authoritative HTTP data. Delivery is best effort, so every (re)connect also refetches.
 */
const invalidationSchema = z.object({
  eventId: z.guid(),
  type: z.enum([
    "bet.changed",
    "bets.changed",
    "chat.changed",
    "table.changed",
    "tables.changed",
    "invitations.changed",
    "profile.changed",
    "settlements.changed",
    "ticker.changed",
  ]),
  schemaVersion: z.literal(1),
  occurredAt: z.string(),
  tableId: z.guid().optional(),
  resourceId: z.guid().optional(),
});

export type Invalidation = z.infer<typeof invalidationSchema>;
export type RealtimeStatus = "connecting" | "live";
type Listener = (event: Invalidation) => void;
type Feed = { listeners: Set<Listener>; subscription: StompSubscription | null };

export const tableViewFeed = (tableId: string) => `/user/queue/tables/${tableId}`;
export const tableBetsFeed = (tableId: string) => `/user/queue/tables/${tableId}/bets`;
export const tableChatFeed = (tableId: string) => `/user/queue/tables/${tableId}/chat`;
export const settlementsFeed = "/user/queue/settlements";
/** Your table list, invitations and the profiles of people you share a table or receipt with. */
export const personalFeed = "/user/queue/me";
/** Sports scores for the ticker, the same for every signed-in player. */
export const tickerFeed = "/user/queue/ticker";

const recentLimit = 200;

export class RealtimeConnection {
  private readonly client: Client;
  private readonly feeds = new Map<string, Feed>();
  private readonly recent = new Set<string>();
  private readonly statusListeners = new Set<() => void>();
  private currentStatus: RealtimeStatus = "connecting";

  constructor(
    private readonly options: {
      /** Called after every successful (re)connect, once subscriptions are in place. */
      onConnected: () => void;
      /** Called whenever the socket closes, for example to check whether the session ended. */
      onClosed: () => void;
      createClient?: (config: StompConfig) => Client;
    },
  ) {
    const create = options.createClient ?? ((config: StompConfig) => new Client(config));
    this.client = create({
      reconnectDelay: 1000,
      maxReconnectDelay: 30_000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      beforeConnect: async (client) => {
        // The CONNECT frame must carry the CSRF token matching the cookie sent with the handshake.
        client.connectHeaders = { "X-CSRF-TOKEN": await csrf() };
      },
      onConnect: () => {
        for (const [destination, feed] of this.feeds)
          feed.subscription = this.subscribe(destination);
        this.setStatus("live");
        this.options.onConnected();
      },
      onWebSocketClose: () => {
        for (const feed of this.feeds.values()) feed.subscription = null;
        this.setStatus("connecting");
        this.options.onClosed();
      },
    });
  }

  get status() {
    return this.currentStatus;
  }

  start() {
    const scheme = window.location.protocol === "https:" ? "wss" : "ws";
    this.client.brokerURL = `${scheme}://${window.location.host}/api/ws`;
    this.client.activate();
  }

  stop() {
    void this.client.deactivate();
  }

  /** Listens to a destination until the returned function is called. */
  listen(destination: string, listener: Listener): () => void {
    let feed = this.feeds.get(destination);
    if (!feed) {
      feed = { listeners: new Set(), subscription: null };
      this.feeds.set(destination, feed);
      if (this.client.connected) feed.subscription = this.subscribe(destination);
    }
    feed.listeners.add(listener);
    return () => {
      const current = this.feeds.get(destination);
      if (!current) return;
      current.listeners.delete(listener);
      if (current.listeners.size > 0) return;
      this.feeds.delete(destination);
      if (this.client.connected) current.subscription?.unsubscribe();
    };
  }

  onStatus(listener: () => void): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  private subscribe(destination: string): StompSubscription {
    return this.client.subscribe(destination, (message) => {
      let parsed: Invalidation;
      try {
        const result = invalidationSchema.safeParse(JSON.parse(message.body));
        if (!result.success) return;
        parsed = result.data;
      } catch {
        return;
      }
      // Invalidations may be redelivered; acting twice is harmless but wasteful.
      if (this.recent.has(parsed.eventId)) return;
      this.recent.add(parsed.eventId);
      if (this.recent.size > recentLimit) this.recent.delete(this.recent.values().next().value!);
      for (const listener of this.feeds.get(destination)?.listeners ?? []) listener(parsed);
    });
  }

  private setStatus(status: RealtimeStatus) {
    if (status === this.currentStatus) return;
    this.currentStatus = status;
    for (const listener of this.statusListeners) listener();
  }
}
