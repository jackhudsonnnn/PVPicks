import { formatCents } from "@/lib/money";
import {
  HOLDEM_LIMITS,
  STACK_BIG_BLINDS,
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

/** Under the big blind slider: everyone's stack, and what a game holds against each player's points limit. */
export function HoldemWorstCase({
  rules,
  bigBlindCents,
}: {
  rules: HoldemRules;
  bigBlindCents: number;
}) {
  return (
    <p className="text-xs text-muted-foreground">
      Everyone starts every hand on{" "}
      <span className="font-semibold text-foreground">
        {formatCents(bigBlindCents * STACK_BIG_BLINDS)}
      </span>{" "}
      ({STACK_BIG_BLINDS} big blinds; small blind {formatCents(Math.floor(bigBlindCents / 2))}).
      Losing it every hand is the worst case:{" "}
      <span className="font-semibold text-foreground">
        {formatCents(holdemWorstCase(bigBlindCents, rules.minPlayers))}
      </span>{" "}
      with {rules.minPlayers} players
      {rules.maxPlayers > rules.minPlayers && (
        <>
          , up to{" "}
          <span className="font-semibold text-foreground">
            {formatCents(holdemWorstCase(bigBlindCents, rules.maxPlayers))}
          </span>{" "}
          with {rules.maxPlayers}
        </>
      )}
      . It's held against each player's points limit while the game is on.
    </p>
  );
}
