import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { settlePairKey } from "../settle-changes";
import { SettleReceiptsDialog } from "./settle-receipts-dialog";

const mocks = vi.hoisted(() => ({ review: vi.fn(), store: { state: {}, actions: {} } }));
vi.mock("@/features/store", () => ({
  useAppStore: () => mocks.store,
  useMe: () => "b",
  useNames: () => ({ name: (id: string) => id }),
  useGetUser: () => (id: string) => ({ id, username: id, hue: 1 }),
}));
vi.mock("./user-avatar", () => ({ UserAvatar: () => null }));

describe("settlement review", () => {
  it("shows a review action and disables payer actions until acknowledged", () => {
    const change = { fromId: "a", toId: "b", before: 2, after: 5, revision: "one" };
    mocks.store.state = {
      meId: "b",
      tables: [],
      messages: [
        {
          id: "one",
          kind: "settlement",
          tableId: "t",
          hostId: "a",
          lines: [{ userId: "b", amount: -5 }],
          hostNet: 5,
          at: "now",
        },
      ],
      settleChanges: { [settlePairKey("a", "b")]: change },
    };
    mocks.store.actions = { reviewSettleChange: mocks.review };
    render(<SettleReceiptsDialog otherUserId="a" />);
    fireEvent.click(screen.getByRole("button", { name: /settle-up receipts/i }));
    expect(screen.getByRole("button", { name: "I sent it" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Ask to forgive" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "I've reviewed this" }));
    expect(mocks.review).toHaveBeenCalledWith("a", change);
  });
});
