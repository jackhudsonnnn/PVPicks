import type { ReactNode } from "react";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { formatCents } from "@/lib/money";
import { RULE_LIMITS, dealerWorstCase, type BlackjackRules } from "@/features/bets/blackjack";
import { Section, Stepper } from "./form-parts";

const JOIN_WINDOWS = [30, 60, 120, 300, 600] as const;

function seconds(sec: number) {
  return sec < 60 ? `${sec}s` : `${sec / 60}m`;
}

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

function Toggle({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description: string;
  checked: boolean;
  onChange: (on: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border bg-surface p-3">
      <span className="min-w-0">
        <span className="block text-sm font-semibold">{label}</span>
        <span className="block text-xs text-muted-foreground">{description}</span>
      </span>
      <Switch checked={checked} onCheckedChange={onChange} aria-label={label} />
    </div>
  );
}

/**
 * The dealer's house rules for a Blackjack hand. Every number is a stepper held inside its minimum and maximum;
 * the worst case shows what the dealer's points limit has to cover.
 */
export function BlackjackSetup({
  rules,
  onChange,
  forFun,
  onForFun,
}: {
  rules: BlackjackRules;
  onChange: (rules: BlackjackRules) => void;
  forFun: boolean;
  onForFun: (on: boolean) => void;
}) {
  const set = <K extends keyof BlackjackRules>(key: K, value: BlackjackRules[K]) =>
    onChange({ ...rules, [key]: value });
  const bet = RULE_LIMITS.betCents;
  return (
    <div className="space-y-6">
      <p className="text-sm text-muted-foreground">
        You deal and bank this hand: players win or lose against you. The cards are shuffled and
        dealt by the app, and the dealer draws to 17 on its own.
      </p>

      <Section label="Bets (points per hand)">
        <div className="grid grid-cols-2 gap-3">
          <Row label="Minimum">
            <Stepper
              value={rules.minBetCents}
              step={10}
              min={bet.min}
              max={rules.maxBetCents}
              label="Minimum bet"
              format={(v) => formatCents(v)}
              onChange={(v) => set("minBetCents", v)}
            />
          </Row>
          <Row label="Maximum">
            <Stepper
              value={rules.maxBetCents}
              step={50}
              min={rules.minBetCents}
              max={bet.max}
              label="Maximum bet"
              format={(v) => formatCents(v)}
              onChange={(v) => set("maxBetCents", v)}
            />
          </Row>
        </div>
        <Toggle
          label="4fun"
          description="No points at stake and nothing tracked. Just bragging rights."
          checked={forFun}
          onChange={onForFun}
        />
      </Section>

      <Section label="Table">
        <div className="grid grid-cols-2 gap-3">
          <Row label="Seats" hint={`${RULE_LIMITS.seats.min}–${RULE_LIMITS.seats.max}`}>
            <Stepper
              value={rules.seats}
              step={1}
              min={RULE_LIMITS.seats.min}
              max={RULE_LIMITS.seats.max}
              label="Seats"
              onChange={(v) => set("seats", v)}
            />
          </Row>
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
          <Row label="Decision timer" hint="10s–2m">
            <Stepper
              value={rules.decisionSec}
              step={5}
              min={RULE_LIMITS.decisionSec.min}
              max={RULE_LIMITS.decisionSec.max}
              label="Decision timer"
              format={seconds}
              onChange={(v) => set("decisionSec", v)}
            />
          </Row>
        </div>
        <Row label="Join window">
          <SegmentedTabs<string>
            size="sm"
            ariaLabel="Join window"
            tabs={JOIN_WINDOWS.map((s) => ({ id: String(s), label: seconds(s) }))}
            value={String(rules.joinSec)}
            onChange={(v) => set("joinSec", Number(v))}
          />
        </Row>
        <Row label="Turns">
          <SegmentedTabs<BlackjackRules["turnOrder"]>
            size="sm"
            ariaLabel="Turns"
            tabs={[
              { id: "together", label: "Everyone at once" },
              { id: "seats", label: "Seat by seat" },
            ]}
            value={rules.turnOrder}
            onChange={(v) => set("turnOrder", v)}
          />
        </Row>
      </Section>

      <Section label="House rules">
        <Row label="Blackjack pays">
          <SegmentedTabs<BlackjackRules["blackjackPays"]>
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
        <Row label="Double down on">
          <SegmentedTabs<BlackjackRules["doubleOn"]>
            size="sm"
            ariaLabel="Double down on"
            tabs={[
              { id: "any", label: "Any two cards" },
              { id: "9-11", label: "9, 10 or 11" },
            ]}
            value={rules.doubleOn}
            onChange={(v) => set("doubleOn", v)}
          />
        </Row>
        <Toggle
          label="Dealer hits soft 17"
          description={
            rules.dealerHitsSoft17 ? "The dealer draws on A-6." : "The dealer stands on every 17."
          }
          checked={rules.dealerHitsSoft17}
          onChange={(on) => set("dealerHitsSoft17", on)}
        />
        <Toggle
          label="Double after split"
          description="Players can double a hand they split."
          checked={rules.doubleAfterSplit}
          onChange={(on) => set("doubleAfterSplit", on)}
        />
        <Toggle
          label="Insurance"
          description="When you show an ace, players can insure for half their bet (pays 2:1)."
          checked={rules.insurance}
          onChange={(on) => set("insurance", on)}
        />
      </Section>

      {!forFun && (
        <p className="rounded-xl border bg-surface p-3 text-xs text-muted-foreground">
          Worst case for you:{" "}
          <span className="font-semibold text-foreground">
            {formatCents(dealerWorstCase(rules))} points
          </span>{" "}
          (every seat at the max bet, every split taken, every hand doubled and won). It has to fit
          your points limit.
        </p>
      )}
    </div>
  );
}
