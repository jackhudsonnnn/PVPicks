import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { Table } from "@/features/table/types";
import { MemberSettingsDialog } from "./member-settings-dialog";

const removeMember = vi.fn();

vi.mock("@/features/store", () => ({
  useMe: () => "host",
  useGetUser: () => (id: string) => ({ id, username: id, hue: 1 }),
  useAppStore: () => ({
    state: { bets: [{ tableId: "table", status: "active" }] },
    actions: { removeMember },
  }),
}));

vi.mock("@/features/social/components/user-avatar", () => ({
  UserAvatar: () => null,
}));

afterEach(() => {
  cleanup();
  removeMember.mockClear();
  delete (HTMLElement.prototype as { scrollIntoView?: unknown }).scrollIntoView;
});

it("offers the permissions shortcut instead of removing during an active bet", async () => {
  const table: Table = {
    id: "table",
    name: "Table",
    emoji: "🏈",
    memberIds: ["host", "member"],
    hostIds: ["host"],
  };
  const scroll = vi.fn();
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
    configurable: true,
    value: scroll,
  });
  render(<MemberSettingsDialog table={table} userId="member" onOpenChange={vi.fn()} />);
  fireEvent.click(screen.getByRole("button", { name: /Kick from table/i }));
  expect(screen.getByText(/Members cannot be removed while bets are active/i)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Adjust permissions" }));
  await waitFor(() => expect(document.activeElement?.id).toBe("perm-bets"));
  expect(scroll).toHaveBeenCalled();
  expect(removeMember).not.toHaveBeenCalled();
});
