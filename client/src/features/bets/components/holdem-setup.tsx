import { formatCents } from "@/lib/money";
import {
  BUY_IN_BIG_BLINDS,
  HOLDEM_LIMITS,
  holdemWorstCase,
  type HoldemRules,
} from "@/features/bets/holdem";
import { Field, RangeSlider, Section, Stepper } from "./form-parts";

/**
 * Hold'em's setup, laid out like every proposal's Game and Setup sections. The big blind and the join window are
 * the sheet's shared wager slider and window tabs, under these; how the mode works is in its info view
 * (`ModeInfo`).
 */
export function HoldemSetup({
  rules,
  onChange,
}: {
  rules: HoldemRules;
  onChange: (rules: HoldemRules) => void;
}) {
  return (
    <>
      <Section label="Setup">
        <Field label="Players" hint={`${HOLDEM_LIMITS.players.min}–${HOLDEM_LIMITS.players.max}`}>
          <RangeSlider
            low={rules.minPlayers}
            high={rules.maxPlayers}
            min={HOLDEM_LIMITS.players.min}
            max={HOLDEM_LIMITS.players.max}
            label="Players"
            unit="players"
            onChange={(minPlayers, maxPlayers) => onChange({ ...rules, minPlayers, maxPlayers })}
          />
        </Field>
        <Field
          label="Rotations"
          hint={`${HOLDEM_LIMITS.rotations.min}–${HOLDEM_LIMITS.rotations.max}`}
        >
          <Stepper
            value={rules.rotations}
            step={1}
            min={HOLDEM_LIMITS.rotations.min}
            max={HOLDEM_LIMITS.rotations.max}
            label="Rotations"
            onChange={(v) => onChange({ ...rules, rotations: v })}
          />
        </Field>
        <Field
          label="Decision timer"
          hint={`${HOLDEM_LIMITS.decisionSec.min}–${HOLDEM_LIMITS.decisionSec.max}s`}
        >
          <Stepper
            value={rules.decisionSec}
            step={5}
            min={HOLDEM_LIMITS.decisionSec.min}
            max={HOLDEM_LIMITS.decisionSec.max}
            label="Decision timer"
            format={(v) => `${v}s`}
            onChange={(v) => onChange({ ...rules, decisionSec: v })}
          />
        </Field>
      </Section>
    </>
  );
}

/** Under the big blind slider: what a game holds against each player's points limit. */
export function HoldemWorstCase({ bigBlindCents }: { bigBlindCents: number }) {
  return (
    <p className="text-xs text-muted-foreground">
      Buy-in{" "}
      <span className="font-semibold text-foreground">
        {formatCents(bigBlindCents * BUY_IN_BIG_BLINDS)}
      </span>{" "}
      (small blind {formatCents(Math.floor(bigBlindCents / 2))}). With the one rebuy, up to{" "}
      <span className="font-semibold text-foreground">
        {formatCents(holdemWorstCase(bigBlindCents))}
      </span>{" "}
      is held against each player's points limit while the game is on.
    </p>
  );
}
