import type { ReactNode } from "react";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { formatCents } from "@/lib/money";
import { PICK_WINDOWS } from "@/features/bets/wager";
import { RULE_LIMITS, dealerWorstCase, type BlackjackRules } from "@/features/bets/blackjack";
import { FunToggle, Section, Stepper, WagerBox, WagerSlider } from "./form-parts";

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
 * The dealer's house rules for a Blackjack hand, laid out like every proposal: the game's rules, the table setup,
 * then the wager (a minimum and a maximum slider, like other modes' wager) and the join window like the pick window.
 * Every number is held inside its minimum and maximum; double after split is always on.
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
      <Section label="Game">
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
          label="Insurance"
          description="When you show an ace, players can insure for half their bet (pays 2:1)."
          checked={rules.insurance}
          onChange={(on) => set("insurance", on)}
        />
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
      </Section>

      <Section label="Setup">
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

      <Section label="Wager (points each)">
        <div className="space-y-3">
          <FunToggle checked={forFun} onChange={onForFun} />
          <WagerBox off={forFun}>
            <WagerSlider
              label="Minimum"
              value={rules.minBetCents}
              min={bet.min}
              max={bet.max}
              disabled={forFun}
              // Raising the minimum past the maximum carries the maximum along.
              onChange={(v) =>
                onChange({ ...rules, minBetCents: v, maxBetCents: Math.max(v, rules.maxBetCents) })
              }
            />
            <WagerSlider
              label="Maximum"
              value={rules.maxBetCents}
              min={bet.min}
              max={bet.max}
              disabled={forFun}
              onChange={(v) =>
                onChange({ ...rules, maxBetCents: v, minBetCents: Math.min(v, rules.minBetCents) })
              }
            />
          </WagerBox>
          {!forFun && (
            <p className="text-xs text-muted-foreground">
              Worst case for you:{" "}
              <span className="font-semibold text-foreground">
                {formatCents(dealerWorstCase(rules))} points
              </span>{" "}
              (every seat at the max bet, every split taken, every hand doubled and won). It has to
              fit your points limit.
            </p>
          )}
        </div>
      </Section>

      <Section label="Join window">
        <SegmentedTabs<string>
          size="sm"
          ariaLabel="Join window"
          tabs={PICK_WINDOWS.map((t) => ({ id: String(t.sec), label: t.label }))}
          value={String(rules.joinSec)}
          onChange={(v) => set("joinSec", Number(v))}
        />
      </Section>
    </div>
  );
}
