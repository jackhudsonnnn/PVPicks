import { describe, expect, it } from "vitest";
import { previewText, type PreviewMessage } from "./previews";

const at = "2026-11-15T18:00:00Z";
const message = (fields: Partial<PreviewMessage>): PreviewMessage => ({
  id: "123e4567-e89b-42d3-a456-426614174000",
  kind: "text",
  createdAt: at,
  ...fields,
});

describe("chat previews", () => {
  it("names the author of text, GIFs and tickets", () => {
    expect(previewText(message({ text: "calling it" }), "samo")).toBe("samo: calling it");
    expect(previewText(message({ kind: "gif" }), "samo")).toBe("samo sent a GIF");
    expect(previewText(message({ kind: "bet" }), "You")).toBe("You sent a ticket");
  });

  it("shows text alone while the name loads", () => {
    expect(previewText(message({ text: "calling it" }), null)).toBe("calling it");
    expect(previewText(message({ kind: "gif" }), null)).toBe("Someone sent a GIF");
  });

  it("describes system messages, settlements and empty chats", () => {
    expect(previewText(message({ kind: "system", text: "samo is now a host" }), null)).toBe(
      "samo is now a host",
    );
    expect(previewText(message({ kind: "settlement" }), null)).toBe("Points settled");
    expect(previewText(null, null)).toBe("No messages yet");
  });
});
