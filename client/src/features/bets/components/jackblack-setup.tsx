import type { ReactNode } from "react";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { formatCents } from "@/lib/money";
import {
  PLAYER_LIMITS,
  RULE_LIMITS,
  worstCase,
  type JackblackRules,
} from "@/features/bets/jackblack";
import { Section, Stepper } from "./form-parts";

function Row({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="grid gap-1.5 text-xs font-medium text-muted-foreground">
      <span>
        {label}
        {hint && <span className="font-normal"> · {hint}</span>}
      </span>
      {children}
    </div>
  );
}

/**
 * Jackblack's own rules, laid out like every proposal's Game and Setup sections. The ante and the join window are
 * the sheet's shared wager slider and window tabs, under these. Every number is held inside its minimum and
 * maximum.
 */
export function JackblackSetup({
  rules,
  onChange,
}: {
  rules: JackblackRules;
  onChange: (rules: JackblackRules) => void;
}) {
  const set = <K extends keyof JackblackRules>(key: K, value: JackblackRules[K]) =>
    onChange({ ...rules, [key]: value });
  return (
    <>
      <Section label="Game">
        <p className="text-sm text-muted-foreground">
          Everyone who joins deals once per rotation, in a random order, and banks that round
          against the rest. Choices are made together: new cards come out once everyone has chosen
          or the timer runs out, so nobody sees anyone's card before deciding.
        </p>
        <Row label="Blackjack pays">
          <SegmentedTabs<JackblackRules["blackjackPays"]>
            size="sm"
            ariaLabel="Blackjack pays"
            tabs={[
              { id: "3:2", label: "3:2" },
              { id: "6:5", label: "6:5" },
            ]}
            value={rules.blackjackPays}
            onChange={(v) => set("blackjackPays", v)}
          />
        </Row>
        <div className="flex items-center justify-between gap-4 rounded-xl border bg-surface p-3">
          <span className="min-w-0">
            <span className="block text-sm font-semibold">Doubling</span>
            <span className="block text-xs text-muted-foreground">
              {rules.doubling
                ? "Players can double any two cards, after splits too."
                : "No doubling down."}
            </span>
          </span>
          <Switch
            checked={rules.doubling}
            onCheckedChange={(on) => set("doubling", on)}
            aria-label="Doubling"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Row label="Decks" hint={`${RULE_LIMITS.decks.min}–${RULE_LIMITS.decks.max}`}>
            <Stepper
              value={rules.decks}
              step={1}
              min={RULE_LIMITS.decks.min}
              max={RULE_LIMITS.decks.max}
              label="Decks"
              onChange={(v) => set("decks", v)}
            />
          </Row>
          <Row
            label="Max splits"
            hint={`${RULE_LIMITS.maxSplits.min}–${RULE_LIMITS.maxSplits.max}`}
          >
            <Stepper
              value={rules.maxSplits}
              step={1}
              min={RULE_LIMITS.maxSplits.min}
              max={RULE_LIMITS.maxSplits.max}
              label="Max splits"
              onChange={(v) => set("maxSplits", v)}
            />
          </Row>
        </div>
      </Section>

      <Section label="Setup">
        <div className="grid grid-cols-2 gap-3">
          <Row label="Rotations" hint="1–3">
            <Stepper
              value={rules.rotations}
              step={1}
              min={RULE_LIMITS.rotations.min}
              max={RULE_LIMITS.rotations.max}
              label="Rotations"
              onChange={(v) => set("rotations", v)}
            />
          </Row>
          <Row label="Decision timer" hint="10–60s">
            <Stepper
              value={rules.decisionSec}
              step={5}
              min={RULE_LIMITS.decisionSec.min}
              max={RULE_LIMITS.decisionSec.max}
              label="Decision timer"
              format={(v) => `${v}s`}
              onChange={(v) => set("decisionSec", v)}
            />
          </Row>
        </div>
      </Section>
    </>
  );
}

/** Under the wager slider: what a game can hold against your points limit. */
export function JackblackWorstCase({
  rules,
  anteCents,
}: {
  rules: JackblackRules;
  anteCents: number;
}) {
  return (
    <p className="text-xs text-muted-foreground">
      Worst case per player:{" "}
      <span className="font-semibold text-foreground">
        {formatCents(worstCase(rules, anteCents, PLAYER_LIMITS.min))}
      </span>{" "}
      with 2 players, up to{" "}
      <span className="font-semibold text-foreground">
        {formatCents(worstCase(rules, anteCents, PLAYER_LIMITS.max))}
      </span>{" "}
      with 7 (every hand split, doubled and lost). It's held against each player's points limit
      while the game is on.
    </p>
  );
}
