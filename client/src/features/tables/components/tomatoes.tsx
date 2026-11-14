import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { motion, useReducedMotionConfig } from "motion/react";
import type { BackendUser } from "./backend-api";
import { newThrows } from "./profile-model";
import { userKey } from "./query-keys";
import { useRealtimeFeed } from "./realtime";
import { tableViewFeed } from "./realtime-connection";
import { listTomatoes, type TomatoThrow } from "./tables-api";
import { usePerson } from "./use-person";

/**
 * Plays every tomato thrown at the table while you're watching it. The realtime feed only says "tomatoes changed";
 * who threw at whom comes from the block-filtered HTTP read. Throws already there when you open the table are not
 * replayed.
 */
export function TomatoLayer({
  tableId,
  user,
  expire,
  peopleKey,
}: {
  tableId: string;
  user: BackendUser;
  expire: () => void;
  peopleKey: readonly string[];
}) {
  const queryClient = useQueryClient();
  const key = userKey(user.id, tableId, "tomatoes");
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => listTomatoes(tableId, signal),
    retry: false,
  });
  useRealtimeFeed(tableViewFeed(tableId), (event) => {
    if (event.type === "tomatoes.changed") void queryClient.invalidateQueries({ queryKey: key });
  });
  const seen = useRef<Set<string> | null>(null);
  const [flying, setFlying] = useState<TomatoThrow[]>([]);
  useEffect(() => {
    if (!query.data) return;
    if (!seen.current) {
      seen.current = new Set(query.data.map((t) => t.id));
      return;
    }
    const fresh = newThrows(query.data, seen.current, 0);
    for (const t of fresh) seen.current.add(t.id);
    if (fresh.length) setFlying((current) => [...current, ...fresh]);
  }, [query.data]);
  return (
    <>
      {flying.map((t) => (
        <TomatoEffect
          key={t.id}
          tomato={t}
          user={user}
          expire={expire}
          peopleKey={peopleKey}
          onComplete={() => setFlying((current) => current.filter((x) => x.id !== t.id))}
        />
      ))}
    </>
  );
}

/** Where someone sits on screen right now: their seat, the "others" spot holding them, or null. */
function seatOf(userId: string) {
  const visible = (element: HTMLElement) => element.getBoundingClientRect().width > 0;
  const seat = Array.from(document.querySelectorAll<HTMLElement>("[data-tomato-user]")).find(
    (element) => element.dataset["tomatoUser"] === userId && visible(element),
  );
  if (seat) return seat;
  return (
    Array.from(document.querySelectorAll<HTMLElement>("[data-tomato-others]")).find(
      (element) =>
        visible(element) &&
        (JSON.parse(element.dataset["tomatoOthers"] ?? "[]") as string[]).includes(userId),
    ) ?? null
  );
}

function TomatoEffect({
  tomato,
  user,
  expire,
  peopleKey,
  onComplete,
}: {
  tomato: TomatoThrow;
  user: BackendUser;
  expire: () => void;
  peopleKey: readonly string[];
  onComplete: () => void;
}) {
  const reduced = !!useReducedMotionConfig();
  const thrower = usePerson(tomato.throwerId, user, expire, peopleKey)?.name ?? "Someone";
  const target = usePerson(tomato.targetId, user, expire, peopleKey)?.name ?? "someone";
  const [path, setPath] = useState<{ x: number; y: number; startX: number; startY: number } | null>(
    null,
  );
  const [landed, setLanded] = useState(false);
  useEffect(() => {
    const destination = seatOf(tomato.targetId);
    const felt = document.querySelector<HTMLElement>("[data-tomato-table]");
    const source = seatOf(tomato.throwerId);
    const to = (destination ?? felt)?.getBoundingClientRect();
    const from = source?.getBoundingClientRect();
    setPath({
      x: to && to.width > 0 ? to.left + to.width / 2 : window.innerWidth / 2,
      y: to && to.width > 0 ? to.top + (destination ? 20 : to.height / 2) : window.innerHeight / 2,
      startX: from ? from.left + from.width / 2 : window.innerWidth / 2,
      startY: from ? from.top + 20 : window.innerHeight - 90,
    });
  }, [tomato.targetId, tomato.throwerId]);
  if (!path) return null;
  return createPortal(
    <div className="pointer-events-none fixed inset-0 z-[100]" data-tomato-effect>
      <motion.span
        className="absolute left-0 top-0 flex size-10 items-center justify-center text-3xl"
        aria-hidden="true"
        initial={
          reduced ? false : { x: path.startX - 20, y: path.startY - 20, scale: 0.6, rotate: -30 }
        }
        animate={
          landed
            ? {
                x: path.x - 20,
                y: path.y - 20,
                scale: reduced ? 1 : [1.4, 1.1],
                rotate: 0,
                opacity: [1, 1, 0],
              }
            : {
                x: reduced
                  ? path.x - 20
                  : [0, 0.25, 0.5, 0.75, 1].map(
                      (t) => path.startX + (path.x - path.startX) * t - 20,
                    ),
                y: reduced
                  ? path.y - 20
                  : [0, 0.25, 0.5, 0.75, 1].map(
                      (t) => path.startY + (path.y - path.startY) * t - 4 * 85 * t * (1 - t) - 20,
                    ),
                scale: 1,
                rotate: reduced ? 0 : 330,
              }
        }
        transition={{
          duration: reduced ? (landed ? 0.45 : 0) : landed ? 0.8 : 1.1,
          ease: landed ? "easeOut" : "linear",
        }}
        onAnimationComplete={() => {
          if (landed) onComplete();
          else setLanded(true);
        }}
      >
        {landed ? <span className="text-loss">✹</span> : "🍅"}
      </motion.span>
      <span role="status" className="sr-only">
        {landed ? `Tomato splat on ${target}!` : `${thrower} threw a tomato at ${target}`}
      </span>
    </div>,
    document.body,
  );
}
