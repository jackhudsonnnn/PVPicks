import { personaFor, type PlayerAccountId as AccountId } from "@/features/auth/demo-accounts";
import type { Profile } from "@/features/settings/profile";
import { deleteUser } from "@/features/settings/deletion";
import type { Blocks } from "@/features/social/safety";
import { getMode } from "@/features/bets/modes";
import { settleBet } from "@/features/bets/payout";
import { PASS, type Bet, type ModeId } from "@/features/bets/types";
import type { Follows } from "@/features/social/follows";
import type { InvitePrefs } from "@/features/social/invites";
import type { Reactions } from "@/features/table/reactions";
import { CHATTER } from "./chatter";
import {
  BETS,
  FOLLOWS,
  GAMES,
  INVITE_PREFS,
  ME,
  MESSAGES,
  TABLE_INVITES,
  TABLES,
  USERS,
} from "./seed";
import type { Message, Table, TableInvite } from "@/features/table/types";
import type { User } from "@/features/social/types";

export interface SeedState {
  bets: Bet[];
  messages: Message[];
  tables: Table[];
  follows: Follows;
  invitePrefs: Record<string, InvitePrefs>;
  tableInvites: TableInvite[];
  reactions: Reactions;
  users: User[];
  blocks: Blocks;
}

/** How lively the sample world is for each demo account. */
export interface Activity {
  /** Tablemates chat on their own. */
  chatter: boolean;
  /** ms between sample chats. */
  chatterMs: [number, number];
  /** A friend adds you to a new table after a while. */
  inviteIn: boolean;
}

export const ACTIVITY: Record<AccountId, Activity> = {
  newbie: { chatter: false, chatterMs: [0, 0], inviteIn: false },
  intermediate: { chatter: false, chatterMs: [0, 0], inviteIn: false },
  pro: { chatter: true, chatterMs: [3000, 6000], inviteIn: true },
};

const REACTIONS: Reactions = {
  s3: { "😂": ["u_jordan", "u_omar"], "🤡": ["u_lena"] },
  s5: { "🔥": ["u_maya"] },
};

/**
 * Sample world for an account, with `me` as the person you are in it
 * (defaults to that demo account's own persona, so no two accounts share a name).
 */
export function seedFor(account: AccountId, me: Profile = personaFor(account)): SeedState {
  const s = account === "newbie" ? newbie() : account === "intermediate" ? intermediate() : pro();
  return { ...s, users: s.users.map((u) => (u.id === ME ? { ...u, ...me } : u)) };
}

function newbie(): SeedState {
  const follows: Follows = { ...FOLLOWS, [ME]: [] };
  for (const k of Object.keys(follows)) follows[k] = follows[k]!.filter((id) => id !== ME);
  return {
    bets: [],
    messages: [],
    tables: TABLES.filter((t) => !t.memberIds.includes(ME)),
    follows,
    invitePrefs: INVITE_PREFS,
    tableInvites: [],
    reactions: {},
    users: USERS,
    blocks: {},
  };
}

function intermediate(): SeedState {
  const bets = BETS.filter((b) => b.status === "resolved" || b.status === "washed");
  const ids = new Set(bets.map((b) => b.id));
  return {
    bets,
    messages: MESSAGES.filter((m) => m.kind !== "bet" || ids.has(m.betId)),
    tables: TABLES,
    follows: FOLLOWS,
    invitePrefs: INVITE_PREFS,
    tableInvites: [],
    reactions: REACTIONS,
    users: USERS,
    blocks: {},
  };
}

const PRO_TABLES: [string, string][] = [
  ["Office Pool", "💼"],
  ["Group Chat FC", "📱"],
  ["Tailgate Crew", "🍔"],
  ["Overtime Club", "⏱️"],
  ["Couch Coaches", "🛋️"],
  ["Pick Six", "6️⃣"],
  ["Buzzer Beaters", "🔔"],
  ["Hail Marys", "🙏"],
  ["Sixth Men", "🪑"],
  ["Line Movers", "📈"],
];

const PRO_MODES: ModeId[] = [
  "either_or",
  "total_disaster",
  "give_and_take",
  "prop_hunt",
  "scorcerer",
  "choose_their_fate",
  "king_of_the_hill",
];

/** Heavy history for load testing: ~12 tables, ~500 past tickets, thousands of chat lines. */
function pro(): SeedState {
  const rand = rng(42);
  const pick = <T>(xs: T[]) => xs[Math.floor(rand() * xs.length)]!;
  const others = USERS.filter((u) => u.id !== ME).map((u) => u.id);

  const extra: Table[] = PRO_TABLES.map(([name, emoji], i) => {
    const size = 3 + Math.floor(rand() * 10);
    const members = shuffle(others, rand).slice(0, size);
    return {
      id: `tp_${i}`,
      name,
      emoji,
      memberIds: [ME, ...members],
      hostIds: [i % 3 === 0 ? ME : members[0]!],
    };
  });
  const tables = [...TABLES, ...extra];
  const mine = tables.filter((t) => t.memberIds.includes(ME));

  const bets: Bet[] = [];
  const messages: Message[] = [];
  let n = 0;
  for (const t of mine) {
    const count = 35 + Math.floor(rand() * 15);
    for (let i = 0; i < count; i++) {
      const game = pick(GAMES);
      const modeId = pick(PRO_MODES);
      const mode = getMode(modeId);
      const config = mode.defaults(game);
      const choices = mode.choices(config, game);
      const proposerId = pick(t.memberIds);
      const players = shuffle(t.memberIds, rand).slice(
        0,
        2 + Math.floor(rand() * (t.memberIds.length - 1)),
      );
      const participants = players.map((userId) => ({
        userId,
        selection: rand() < 0.1 ? PASS : pick(choices),
      }));
      const wager = [0.5, 1, 1, 2, 2.5, 5][Math.floor(rand() * 6)]!;
      const winningChoice = pick(choices);
      const r = settleBet(participants, wager, choices, winningChoice, {
        mode: "host",
        hostId: t.hostIds[0]!,
        proposerId,
        seed: `pb_${n}`,
      });
      const day = 1 + Math.floor(i / 6);
      const at = `Oct ${day} ${1 + (i % 11)}:${String((i * 7) % 60).padStart(2, "0")} PM`;
      const id = `pb_${n++}`;
      bets.push({
        id,
        tableId: t.id,
        proposerId,
        modeId,
        gameId: game.id,
        config,
        question: mode.question(config, game),
        choices,
        wager,
        durationSec: 120,
        closesInSec: 0,
        status: r.status,
        participants,
        winningChoice,
        washReason: r.status === "washed" ? r.reason : undefined,
        deltas: r.deltas,
        // Older history is already settled; the last few per table still count.
        settled: i < count - 4,
        createdAt: at,
        lockedAt: at,
        closedAt: at,
      });
      messages.push({
        id: `pm_${n}_b`,
        tableId: t.id,
        kind: "bet",
        authorId: proposerId,
        betId: id,
        at,
      });
      for (let k = 0; k < 4; k++)
        messages.push({
          id: `pm_${n}_${k}`,
          tableId: t.id,
          kind: "text",
          authorId: pick(t.memberIds),
          text: pick(CHATTER),
          at,
        });
    }
  }

  // Live tickets and recent chat from the regular sample data come last (newest).
  // One sample player deleted their account, so "Deleted user" shows up in old tickets.
  return deleteUser(
    {
      bets: [...bets, ...BETS],
      messages: [...messages, ...MESSAGES],
      tables,
      follows: FOLLOWS,
      invitePrefs: INVITE_PREFS,
      tableInvites: TABLE_INVITES,
      reactions: REACTIONS,
      users: USERS,
      blocks: {},
    },
    DELETED_SAMPLE,
  );
}

/** Pro's sample player who deleted their account. */
const DELETED_SAMPLE = "u_nina";

function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function shuffle<T>(xs: T[], rand: () => number) {
  const out = [...xs];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  return out;
}
