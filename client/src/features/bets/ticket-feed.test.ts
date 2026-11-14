import { describe, expect, it } from "vitest";
import { FIRST_TICKET_PAGE, nextTicketPage } from "./ticket-feed";

describe("home ticket feed", () => {
  it("pages through each status before moving to the next, live first", () => {
    expect(FIRST_TICKET_PAGE).toEqual({ status: "ACTIVE", cursor: null });
    expect(nextTicketPage({ items: [], nextCursor: "abc" }, FIRST_TICKET_PAGE)).toEqual({
      status: "ACTIVE",
      cursor: "abc",
    });
    expect(
      nextTicketPage({ items: [], nextCursor: null }, { status: "ACTIVE", cursor: "abc" }),
    ).toEqual({
      status: "PENDING",
      cursor: null,
    });
    expect(
      nextTicketPage({ items: [], nextCursor: null }, { status: "SETTLED", cursor: null }),
    ).toEqual({
      status: "WASHED",
      cursor: null,
    });
  });

  it("ends after washed bets", () => {
    expect(
      nextTicketPage({ items: [], nextCursor: null }, { status: "WASHED", cursor: null }),
    ).toBeUndefined();
  });
});
