import type { Client, IMessage, StompConfig } from "@stomp/stompjs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { csrf } from "./backend-api";
import { RealtimeConnection, tableBetsFeed } from "./realtime-connection";

vi.mock("./backend-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./backend-api")>()),
  csrf: vi.fn(),
}));

const table = "123e4567-e89b-42d3-a456-426614174001";
const bet = "123e4567-e89b-42d3-a456-426614174002";

/** A controllable stand-in for the STOMP client. */
class FakeClient {
  connected = false;
  brokerURL = "";
  connectHeaders = {};
  subscriptions = new Map<string, (message: IMessage) => void>();
  unsubscribed: string[] = [];
  constructor(readonly config: StompConfig) {}
  activate() {}
  deactivate() {
    return Promise.resolve();
  }
  subscribe(destination: string, callback: (message: IMessage) => void) {
    this.subscriptions.set(destination, callback);
    return { id: destination, unsubscribe: () => this.unsubscribed.push(destination) };
  }
  async connect() {
    await this.config.beforeConnect?.(this as unknown as Client);
    this.connected = true;
    this.config.onConnect?.({} as never);
  }
  close() {
    this.connected = false;
    this.subscriptions.clear();
    this.config.onWebSocketClose?.({} as never);
  }
  deliver(destination: string, body: object | string) {
    const message = { body: typeof body === "string" ? body : JSON.stringify(body) } as IMessage;
    this.subscriptions.get(destination)?.(message);
  }
}

function setup() {
  let client!: FakeClient;
  const onConnected = vi.fn();
  const onClosed = vi.fn();
  const connection = new RealtimeConnection({
    onConnected,
    onClosed,
    createClient: (config) => {
      client = new FakeClient(config);
      return client as unknown as Client;
    },
  });
  return { connection, client, onConnected, onClosed };
}

const betChanged = {
  eventId: "123e4567-e89b-42d3-a456-426614174010",
  type: "bet.changed",
  schemaVersion: 1,
  occurredAt: "2026-11-13T12:00:00Z",
  tableId: table,
  resourceId: bet,
};

beforeEach(() => {
  vi.mocked(csrf).mockResolvedValue("csrf-token");
});

describe("RealtimeConnection", () => {
  it("sends the CSRF token on CONNECT and refetches after every (re)connect", async () => {
    const { client, onConnected, onClosed } = setup();
    await client.connect();
    expect(client.connectHeaders).toEqual({ "X-CSRF-TOKEN": "csrf-token" });
    expect(onConnected).toHaveBeenCalledOnce();
    client.close();
    expect(onClosed).toHaveBeenCalledOnce();
    await client.connect();
    expect(onConnected).toHaveBeenCalledTimes(2);
  });

  it("subscribes listened feeds on connect, resubscribes after reconnect, and unsubscribes", async () => {
    const { connection, client } = setup();
    const listener = vi.fn();
    const stop = connection.listen(tableBetsFeed(table), listener);
    await client.connect();
    client.deliver(tableBetsFeed(table), betChanged);
    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ resourceId: bet }));
    client.close();
    await client.connect();
    client.deliver(tableBetsFeed(table), {
      ...betChanged,
      eventId: "123e4567-e89b-42d3-a456-426614174011",
    });
    expect(listener).toHaveBeenCalledTimes(2);
    stop();
    expect(client.unsubscribed).toEqual([tableBetsFeed(table)]);
  });

  it("ignores duplicates and messages that do not match the contract", async () => {
    const { connection, client } = setup();
    const listener = vi.fn();
    connection.listen(tableBetsFeed(table), listener);
    await client.connect();
    client.deliver(tableBetsFeed(table), betChanged);
    client.deliver(tableBetsFeed(table), betChanged);
    client.deliver(tableBetsFeed(table), { ...betChanged, eventId: "not-a-uuid" });
    client.deliver(tableBetsFeed(table), "not json");
    expect(listener).toHaveBeenCalledOnce();
  });

  it("reports live and reconnecting status", async () => {
    const { connection, client } = setup();
    const changed = vi.fn();
    connection.onStatus(changed);
    expect(connection.status).toBe("connecting");
    await client.connect();
    expect(connection.status).toBe("live");
    client.close();
    expect(connection.status).toBe("connecting");
    expect(changed).toHaveBeenCalledTimes(2);
  });
});
