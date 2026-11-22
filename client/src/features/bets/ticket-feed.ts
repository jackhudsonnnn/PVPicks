import type { Bet, BetPage } from "./api";

type Status = Bet["status"];

/** Home's ticket order: live first, then waiting on results, then finished. Newest first within each. */
const TICKET_ORDER: readonly Status[] = ["ACTIVE", "PENDING", "SETTLED", "WASHED"];

interface TicketPageParam {
  status: Status;
  cursor: string | null;
}

export const FIRST_TICKET_PAGE: TicketPageParam = { status: TICKET_ORDER[0]!, cursor: null };

/** The page after `page` (fetched with `param`): more of the same status, then the next status, then none. */
export function nextTicketPage(page: BetPage, param: TicketPageParam): TicketPageParam | undefined {
  if (page.nextCursor) return { status: param.status, cursor: page.nextCursor };
  const next = TICKET_ORDER[TICKET_ORDER.indexOf(param.status) + 1];
  return next ? { status: next, cursor: null } : undefined;
}
