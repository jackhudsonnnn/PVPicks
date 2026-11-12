import type { RemainderMode, SettlementLine } from "@/features/bets/payout";
import type { MemberPermission } from "./hosts";

export interface Table {
  id: string;
  name: string;
  emoji: string;
  memberIds: string[];
  /** Members who run the table (always at least one). */
  hostIds: string[];
  /** Who gets leftover cents on uneven pots; defaults to the host. */
  remainderMode?: RemainderMode;
  /** When true, only hosts can start new bets. */
  hostsOnlyBets?: boolean;
  /** When true, a host has turned off U2Pick (custom) bets at this table. */
  u2pickOff?: boolean;
  /** Per member: permissions a host turned off. Missing = everything allowed. */
  limits?: Record<string, MemberPermission[]>;
  /** Quick Picks turned on by a host (see quick-picks.ts). */
  quickPicksOn?: boolean;
  /** Set = this table shares its proposals; other hosts subscribe with this code. */
  shareCode?: string | undefined;
  /** Tables whose proposals auto-post here. */
  subscribedTo?: string[];
}

export type PaymentStatus = "unpaid" | "sent" | "paid" | "forgiven";

export type Message =
  | { id: string; tableId: string; kind: "text"; authorId: string; text: string; at: string }
  | { id: string; tableId: string; kind: "bet"; authorId: string; betId: string; at: string }
  | { id: string; tableId: string; kind: "gif"; authorId: string; gifId: string; at: string }
  | { id: string; tableId: string; kind: "system"; text: string; at: string }
  | {
      id: string;
      tableId: string;
      kind: "settlement";
      hostId: string;
      lines: SettlementLine[];
      hostNet: number;
      /** Per line (keyed by line userId): unpaid → sent (payer) → paid (receiver confirms). */
      payments?: Record<string, PaymentStatus>;
      /** Per line: the payer's request to forgive it (see settlements.ts). */
      forgiveAsks?: Record<string, { status: "open" | "declined"; at: number }>;
      at: string;
    };

/** Any message without its id; the store assigns ids when it posts one. */
export type NewMessage = Message extends infer M
  ? M extends Message
    ? Omit<M, "id">
    : never
  : never;

/** A host asked `userId` to join `tableId`; waiting for them to accept or decline. */
export interface TableInvite {
  id: string;
  tableId: string;
  fromId: string;
  userId: string;
}
