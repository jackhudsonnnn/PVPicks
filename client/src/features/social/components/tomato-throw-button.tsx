import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { motion, useReducedMotionConfig } from "motion/react";
import { Button } from "@/components/ui/button";

/** A local, playful profile effect; never submits a report or changes points. */
export function TomatoThrowButton({ name, onThrow, disabled = false }: { name: string; onThrow: () => void; disabled?: boolean }) {
  return (
    <Button
      size="sm"
      variant="outline"
      aria-label={`Throw a tomato at ${name}`}
      title={disabled ? "Pick a table to throw a tomato" : `Throw a tomato at ${name}`}
      disabled={disabled}
      onClick={onThrow}
    >
      <span aria-hidden="true" className="text-base leading-none">
        🍅
      </span>{" "}
      Throw
    </Button>
  );
}

/** Mounted outside the profile dialog so closing it cannot remove the throw. */
export function TomatoThrowEffect({
  userId,
  name,
  onComplete,
}: {
  userId: string;
  name: string;
  onComplete: () => void;
}) {
  const reduced = !!useReducedMotionConfig();
  const [target, setTarget] = useState<{
    x: number;
    y: number;
    startX: number;
    startY: number;
  } | null>(null);
  const [landed, setLanded] = useState(false);
  useEffect(() => {
    // Let the dialog's 200ms closing transition finish before starting.
    const timer = window.setTimeout(() => {
      const seat = Array.from(document.querySelectorAll<HTMLElement>("[data-tomato-user]")).find(
        (element) =>
          element.dataset["tomatoUser"] === userId && element.getBoundingClientRect().width > 0,
      );
      const others = Array.from(document.querySelectorAll<HTMLElement>("[data-tomato-others]")).find(
        (element) =>
          (JSON.parse(element.dataset["tomatoOthers"] ?? "[]") as string[]).includes(userId) &&
          element.getBoundingClientRect().width > 0,
      );
      // Overflow peers share their existing rail seat; unseated peers land on
      // the felt instead of creating a temporary floating profile.
      const destination = seat ?? others ?? document.querySelector<HTMLElement>("[data-tomato-table]");
      const bounds = destination?.getBoundingClientRect();
      setTarget({
        x: bounds ? bounds.left + bounds.width / 2 : window.innerWidth / 2,
        y: bounds ? bounds.top + (seat || others ? 20 : bounds.height / 2) : window.innerHeight / 2,
        startX: window.innerWidth / 2,
        startY: window.innerHeight - 90,
      });
    }, 250);
    return () => window.clearTimeout(timer);
  }, [userId]);
  if (!target) return null;
  return createPortal(
    <div className="pointer-events-none fixed inset-0 z-[100]" data-tomato-effect>
      <motion.span
        className="absolute left-0 top-0 flex size-10 items-center justify-center text-3xl"
        aria-hidden="true"
        initial={
          reduced
            ? false
            : { x: target.startX - 20, y: target.startY - 20, scale: 0.6, rotate: -30 }
        }
        animate={
          landed
            ? {
                x: target.x - 20,
                y: target.y - 20,
                scale: reduced ? 1 : [1.4, 1.1],
                rotate: 0,
                opacity: [1, 1, 0],
              }
            : {
                x: reduced ? target.x - 20 : [0, 0.25, 0.5, 0.75, 1].map(
                  (t) => target.startX + (target.x - target.startX) * t - 20,
                ),
                y: reduced ? target.y - 20 : [0, 0.25, 0.5, 0.75, 1].map(
                  (t) => target.startY + (target.y - target.startY) * t - 4 * 85 * t * (1 - t) - 20,
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
        {landed ? `Tomato splat on ${name}!` : `Throwing a tomato at ${name}`}
      </span>
    </div>,
    document.body,
  );
}
