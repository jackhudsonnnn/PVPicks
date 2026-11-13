import { useEffect, useMemo, useRef, type Dispatch, type RefObject } from "react";
import type { PlayerAccountId } from "@/features/auth/demo-accounts";
import type { Bet } from "@/features/bets/types";
import { eligibleSettlers, canSettleCustom } from "@/features/bets/u2pick";
import { GIFS } from "@/features/table/gifs";
import { copyId, isSharing, subscribersOf } from "@/features/table/quick-picks";
import { QUICK_REACTIONS } from "@/features/table/reactions";
import { canAskForgive, paymentsOf } from "@/features/table/settlements";
import type { SampleReplies } from "@/features/store/commands";
import type { Action, AppState } from "@/features/store/state";
import { newId } from "@/lib/ids";
import { nowLabel } from "@/lib/time";
import { CHATTER } from "./chatter";
import { ACTIVITY } from "./profiles";

/**
 * The pretend world around you: tablemates chat, join your bets, call U2Picks and answer
 * what you do, so the prototype feels alive without a server.
 * TODO(backend): delete this file; real people's actions arrive over the WebSocket.
 */

/** Sample stadium U2Picks for the Quick Picks demo feed. */
const ARENA_ID = "t_arena";
const ARENA_PROPS = [
  { question: "Kiss cam lands on a couple that refuses?", choices: ["Yes", "No"] },
  { question: "T-shirt cannon reaches the upper deck?", choices: ["Yes", "No"] },
  {
    question: "Which side wins the next crowd noise meter?",
    choices: ["Home side", "Away side", "Tie"],
  },
  { question: "Mascot falls over during the timeout skit?", choices: ["Yes", "No"] },
  { question: "Halftime half-court shot goes in?", choices: ["Yes", "No"] },
];

const pickOne = <T>(xs: readonly T[]): T | undefined => xs[Math.floor(Math.random() * xs.length)];

/** setTimeout that is cancelled when the store unmounts (signing out, switching accounts). */
function useTimers() {
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());
  useEffect(() => {
    const set = timers.current;
    return () => {
      for (const t of set) clearTimeout(t);
      set.clear();
    };
  }, []);
  return useMemo(
    () => (fn: () => void, ms: number) => {
      const t = setTimeout(() => {
        timers.current.delete(t);
        fn();
      }, ms);
      timers.current.add(t);
    },
    [],
  );
}

/** Runs the sample world for `account` and returns how it answers your commands. */
export function useSampleActivity(
  account: PlayerAccountId,
  state: AppState,
  stateRef: RefObject<AppState>,
  dispatch: Dispatch<Action>,
): SampleReplies {
  const activity = ACTIVITY[account];
  const later = useTimers();
  const get = () => stateRef.current!;

  // Tablemates chat every so often to simulate activity.
  useEffect(() => {
    if (!activity.chatter) return;
    let t: ReturnType<typeof setTimeout>;
    const next = () => {
      t = setTimeout(
        () => {
          const s = get();
          const table = pickOne(
            s.tables.filter((x) => x.memberIds.length > 1 && x.memberIds.includes(s.meId)),
          );
          const author = table && pickOne(table.memberIds.filter((m) => m !== s.meId));
          if (table && author) {
            if (Math.random() < 0.3)
              dispatch({
                type: "gif",
                tableId: table.id,
                authorId: author,
                gifId: pickOne(GIFS)!.id,
                at: nowLabel(),
              });
            else
              dispatch({
                type: "chat",
                tableId: table.id,
                authorId: author,
                text: pickOne(CHATTER)!,
                at: nowLabel(),
              });
          }
          next();
        },
        activity.chatterMs[0] + Math.random() * activity.chatterMs[1],
      );
    };
    next();
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reads live state through the ref
  }, [activity]);

  // When a locked U2Pick is someone else's call, a tablemate who can settle it calls it a bit
  // later so the demo doesn't stall. Tables you don't sit at (the stadium feed) call every
  // ticket themselves; their game bets get a sample result.
  const scheduled = useRef(new Set<string>());
  useEffect(() => {
    for (const b of state.bets) {
      if (b.status !== "pending" || b.quickPick || scheduled.current.has(b.id)) continue;
      const t = state.tables.find((x) => x.id === b.tableId);
      if (!t) continue;
      const away = !t.memberIds.includes(state.meId);
      const custom = b.modeId === "u2pick";
      if (!custom && !away) continue;
      if (custom && !away && canSettleCustom(b, t, state.meId)) continue;
      const actor = custom
        ? pickOne(eligibleSettlers(b, t).filter((m) => m !== state.meId))
        : b.proposerId;
      if (!actor) continue;
      scheduled.current.add(b.id);
      later(() => {
        // Let it be scheduled again if the call didn't go through (e.g. the settler left).
        scheduled.current.delete(b.id);
        const winningChoice = pickOne(b.choices) ?? null;
        dispatch(
          custom
            ? { type: "settle", betId: b.id, winningChoice, actorId: actor, at: nowLabel() }
            : { type: "gameResult", betId: b.id, winningChoice, at: nowLabel() },
        );
      }, 8000);
    }
  }, [state.bets, state.tables, state.meId, later, dispatch]);

  // A friend starts a new table and adds you, so your table-invite settings show.
  useEffect(() => {
    if (!activity.inviteIn) return;
    const t = setTimeout(() => {
      dispatch({
        type: "inviteIn",
        fromId: "u_theo",
        table: {
          id: "t_parlay",
          name: "Parlay Lab",
          emoji: "🎲",
          memberIds: ["u_theo", "u_maya"],
          hostIds: ["u_theo"],
        },
        at: nowLabel(),
      });
    }, 25000);
    return () => clearTimeout(t);
  }, [activity, dispatch]);

  // Busy accounts: someone who owes you asks you to forgive it.
  useEffect(() => {
    if (!activity.chatter) return;
    const t = setTimeout(() => {
      const s = get();
      for (const m of s.messages) {
        if (m.kind !== "settlement") continue;
        const p = paymentsOf(m).find(
          (x) => x.to === s.meId && canAskForgive(x, x.from, Date.now()),
        );
        if (!p) continue;
        dispatch({
          type: "forgiveAsk",
          messageId: m.id,
          lineUserId: p.lineUserId,
          answer: "ask",
          actorId: p.from,
          now: Date.now(),
        });
        return;
      }
    }, 15000);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reads live state through the ref
  }, [activity]);

  const replies = useMemo<SampleReplies>(() => {
    /** Tablemates jump in over the next few seconds, at the source and every Quick Pick copy. */
    const proposed = (betId: string, tableId: string, choices: string[]) => {
      const s = get();
      const source = s.tables.find((t) => t.id === tableId);
      if (!source) return;
      const targets = [
        { betId, table: source },
        ...(isSharing(source)
          ? subscribersOf(s.tables, source.id).map((t) => ({
              betId: copyId(betId, t.id),
              table: t,
            }))
          : []),
      ];
      for (const { betId: id, table } of targets)
        for (const m of table.memberIds.filter((x) => x !== s.meId))
          later(
            () => dispatch({ type: "join", betId: id, userId: m, selection: pickOne(choices) }),
            1500 + Math.random() * 6000,
          );
    };

    /** The person on the other side of a settle-up line, if it isn't you. */
    const otherSide = (messageId: string, lineUserId: string) => {
      const s = get();
      const m = s.messages.find((x) => x.id === messageId);
      const to =
        m?.kind === "settlement"
          ? paymentsOf(m).find((p) => p.lineUserId === lineUserId)?.to
          : undefined;
      return to && to !== s.meId ? to : undefined;
    };

    return {
      proposed,
      // A tablemate sometimes reacts to what you just sent.
      chatted: (tableId) =>
        later(
          () => {
            const s = get();
            const mine = s.messages.filter(
              (m) => m.tableId === tableId && "authorId" in m && m.authorId === s.meId,
            );
            const target = mine[mine.length - 1];
            const who = pickOne(
              s.tables.find((t) => t.id === tableId)?.memberIds.filter((m) => m !== s.meId) ?? [],
            );
            if (!target || !who || Math.random() < 0.3) return;
            dispatch({
              type: "react",
              messageId: target.id,
              emoji: pickOne(QUICK_REACTIONS)!,
              userId: who,
            });
          },
          2000 + Math.random() * 3000,
        ),
      // Most people follow you back a few seconds later.
      followed: (userId) => {
        const s = get();
        if ((s.follows[userId] ?? []).includes(s.meId) || Math.random() >= 0.6) return;
        later(() => dispatch({ type: "follow", from: userId, to: s.meId, on: true }), 3000);
      },
      // The person you paid confirms a few seconds later.
      markedSent: (messageId, lineUserId) => {
        const to = otherSide(messageId, lineUserId);
        if (to)
          later(
            () => dispatch({ type: "payment", messageId, lineUserId, status: "paid", actorId: to }),
            4000,
          );
      },
      // The person you owe answers a few seconds later, usually with a yes.
      askedForgive: (messageId, lineUserId) => {
        const to = otherSide(messageId, lineUserId);
        if (!to) return;
        later(() => {
          if (Math.random() < 0.6)
            dispatch({ type: "payment", messageId, lineUserId, status: "forgiven", actorId: to });
          else
            dispatch({
              type: "forgiveAsk",
              messageId,
              lineUserId,
              answer: "decline",
              actorId: to,
              now: Date.now(),
            });
        }, 5000);
      },
      // People who got a request accept it a few seconds later.
      addedMembers: (tableId, userIds) =>
        later(() => {
          for (const inv of get().tableInvites)
            if (inv.tableId === tableId && userIds.includes(inv.userId))
              dispatch({
                type: "respondInvite",
                inviteId: inv.id,
                actorId: inv.userId,
                accept: true,
                at: nowLabel(),
              });
        }, 5000),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reads live state through the ref
  }, [later, dispatch]);

  // While anyone subscribes to the stadium feed, it posts a U2Pick now and then.
  const arenaFed = state.tables.some((t) => (t.subscribedTo ?? []).includes(ARENA_ID));
  useEffect(() => {
    if (!arenaFed) return;
    let n = 0;
    const postProp = () => {
      if (get().bets.some((b) => b.tableId === ARENA_ID && b.status === "active")) return;
      const q = ARENA_PROPS[n++ % ARENA_PROPS.length]!;
      const bet: Bet = {
        id: newId("b"),
        tableId: ARENA_ID,
        proposerId: "u_nina",
        modeId: "u2pick",
        config: { question: q.question, options: q.choices },
        question: q.question,
        choices: q.choices,
        settledBy: "hosts",
        wager: 1,
        durationSec: 60,
        closesInSec: 60,
        status: "active",
        participants: [],
        createdAt: nowLabel(),
      };
      dispatch({ type: "propose", bet, at: nowLabel() });
      replies.proposed(bet.id, bet.tableId, bet.choices);
    };
    const first = setTimeout(postProp, 2000);
    const t = setInterval(postProp, 45000);
    return () => {
      clearTimeout(first);
      clearInterval(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reads live state through the ref
  }, [arenaFed, replies, dispatch]);

  return replies;
}
