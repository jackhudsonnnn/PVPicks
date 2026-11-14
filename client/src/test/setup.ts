import { vi } from "vitest";
import "@testing-library/jest-dom/vitest";

globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

Object.defineProperty(window, "scrollTo", {
  writable: true,
  value: () => {},
});

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => {},
  }),
});

// Node 22+ defines its own `localStorage` global, which shadows jsdom's and is unusable without
// --localstorage-file. Replace it with an in-memory Storage so browser storage code runs in tests.
if (!(globalThis.localStorage as Storage | undefined)?.setItem) {
  class MemoryStorage implements Storage {
    #items = new Map<string, string>();
    get length() {
      return this.#items.size;
    }
    clear() {
      this.#items.clear();
    }
    getItem(key: string) {
      return this.#items.get(key) ?? null;
    }
    key(index: number) {
      return [...this.#items.keys()][index] ?? null;
    }
    removeItem(key: string) {
      this.#items.delete(key);
    }
    setItem(key: string, value: string) {
      this.#items.set(key, String(value));
    }
  }
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    writable: true,
    value: new MemoryStorage(),
  });
}

// Components never open real WebSockets in tests; realtime.test.ts injects its own fake client.
vi.mock("@stomp/stompjs", () => ({
  ReconnectionTimeMode: { LINEAR: 0, EXPONENTIAL: 1 },
  Client: class {
    connected = false;
    brokerURL = "";
    activate() {}
    deactivate() {
      return Promise.resolve();
    }
    subscribe() {
      return { id: "inert", unsubscribe() {} };
    }
  },
}));

// Radix Select uses pointer capture and scrollIntoView, which jsdom doesn't implement.
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.releasePointerCapture ??= () => {};
Element.prototype.scrollIntoView ??= () => {};
