import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithQuery } from "@/test/render";
import { user } from "@/test/api-fixtures";
import { getProfile } from "@/features/people/api";
import { OthersDialog } from "./components/others-dialog";

vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));

describe("+N others", () => {
  it("lists who didn't fit at the felt, and opens a profile without leaving the table", async () => {
    const ann = "00000000-0000-4000-8000-0000000000a1";
    const ben = "00000000-0000-4000-8000-0000000000b2";
    vi.mocked(getProfile).mockImplementation(async (id) => ({
      id,
      username: id === ann ? "ann" : "ben",
      hue: 1,
    }));
    const onSelectUser = vi.fn();
    renderWithQuery(
      <OthersDialog
        ids={[ann, ben]}
        open
        onOpenChange={() => {}}
        onSelectUser={onSelectUser}
        user={user}
        expire={() => {}}
        peopleKey={["people"]}
      />,
    );
    expect(screen.getByRole("heading", { name: "2 more at the table" })).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: /ben/ }));
    expect(onSelectUser).toHaveBeenCalledWith(ben);
  });
});
