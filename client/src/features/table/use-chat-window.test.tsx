import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useChatWindow } from "./use-chat-window";
import type { Message } from "./types";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const history: Message[] = Array.from({ length: 5000 }, (_, i) => ({
  id: `m${i}`,
  tableId: "table",
  kind: "text",
  authorId: "peer",
  text: `Message ${i}`,
  at: "now",
}));

function Harness({
  messages,
  save,
}: {
  messages: Message[];
  save: (id: string | undefined) => void;
}) {
  const chat = useChatWindow(messages, true, "m999", "me", save);
  return (
    <>
      <button onClick={chat.jumpToLatest}>Jump</button>
      <div ref={chat.scroller} onScroll={chat.onScroll} data-testid="scroll">
        {messages.slice(chat.window?.start ?? 0, chat.window?.end ?? 0).map((m) => (
          <div key={m.id} data-chat-message={m.id}>
            {m.id === chat.dividerId && <span data-unread-line />}{" "}
            {m.kind === "text" ? m.text : m.id}
          </div>
        ))}
      </div>
    </>
  );
}

describe("mounted chat history", () => {
  it("includes a sent message when jumping and receiving it in the same update", () => {
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    const save = vi.fn();
    const { container, rerender } = render(<Harness messages={history} save={save} />);
    const sent: Message = {
      id: "sent",
      tableId: "table",
      kind: "text",
      authorId: "me",
      text: "Sent now",
      at: "now",
    };
    act(() => {
      fireEvent.click(screen.getByText("Jump"));
      rerender(<Harness messages={[...history, sent]} save={save} />);
    });
    expect(container.querySelector('[data-chat-message="sent"]')).not.toBeNull();
    expect(save).toHaveBeenLastCalledWith("sent");
    expect(container.querySelectorAll("[data-chat-message]").length).toBeLessThanOrEqual(60);
  });
  it("mounts only first-unread context, replaces the gap on jump, and bounds incoming messages", () => {
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    const save = vi.fn();
    const { container, rerender } = render(<Harness messages={history} save={save} />);
    expect(container.querySelectorAll("[data-chat-message]")).toHaveLength(30);
    expect(container.querySelector("[data-chat-message]")?.getAttribute("data-chat-message")).toBe(
      "m995",
    );
    expect(
      container
        .querySelector("[data-unread-line]")
        ?.parentElement?.getAttribute("data-chat-message"),
    ).toBe("m1000");
    expect(save).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Jump"));
    expect(container.querySelectorAll("[data-chat-message]")).toHaveLength(30);
    expect(container.querySelector("[data-chat-message]")?.getAttribute("data-chat-message")).toBe(
      "m4970",
    );
    expect(save).toHaveBeenLastCalledWith("m4999");
    const incoming: Message[] = [
      ...history,
      ...Array.from({ length: 100 }, (_, i) => ({ ...history[0], id: `new${i}` }) as Message),
    ];
    act(() => rerender(<Harness messages={incoming} save={save} />));
    expect(container.querySelectorAll("[data-chat-message]")).toHaveLength(60);
    expect(save).toHaveBeenLastCalledWith("new99");
  });
});
