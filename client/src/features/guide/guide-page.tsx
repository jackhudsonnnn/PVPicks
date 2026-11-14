import { Link, useRouterState } from "@tanstack/react-router";
import {
  Radio,
  Zap,
  Megaphone,
  Ban,
  Flag,
  Trash2,
  MessagesSquare,
  ArrowRight,
  BadgeDollarSign,
  Heart,
  HeartHandshake,
  Scale,
  ShieldCheck,
  Users,
  Handshake,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { PageHeader } from "@/components/page-header";
import { useEffect, useState } from "react";
import { MODES } from "@/features/bets/modes";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";

import { StatusBadge } from "@/features/bets/components/status-badge";
import {
  FILTERS,
  LIFECYCLE,
  SECTIONS,
  MONEY_POINTS,
  SOCIAL_POINTS,
  SETTLE_POINTS,
  QUICK_PICK_POINTS,
  SAFETY_POINTS,
  type ModeFilter,
} from "../constants";

function ModesSection() {
  const [filter, setFilter] = useState<ModeFilter>("all");
  // Game modes work for both leagues; U2Pick is the custom, game-free mode.
  const shown = MODES.filter((m) =>
    filter === "all" ? true : filter === "u2pick" ? m.custom : !m.custom,
  );
  return (
    <section id="modes" className="scroll-mt-20 space-y-5">
      <h2 className="text-4xl font-black uppercase">Game modes</h2>
      <SegmentedTabs
        size="sm"
        ariaLabel="Filter game modes"
        tabs={FILTERS}
        value={filter}
        onChange={setFilter}
        className="max-w-md"
      />
      <ScrollContainer
        isHorizontal
        isVisible={false}
        className="-mx-4 snap-x gap-3 px-4 pb-2 md:mx-0 md:px-0"
      >
        {shown.map((m) => {
          const Icon = m.icon;
          return (
            <div
              key={m.id}
              className="flex w-72 shrink-0 snap-start gap-4 rounded-xl border bg-surface p-5"
            >
              <span className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
                <Icon className="size-5" />
              </span>
              <div>
                <h3 className="text-2xl font-extrabold uppercase leading-none">{m.name}</h3>
                <p className="mt-0.5 font-mono text-[11px] uppercase tracking-wider text-primary">
                  {m.tagline}
                </p>
                <p className="mt-2 text-sm text-muted-foreground">{m.description}</p>
              </div>
            </div>
          );
        })}
      </ScrollContainer>
    </section>
  );
}

/** The player guide. `feedback` is the feedback form, supplied by the app so the guide stays static. */
export function HowToPlay({ feedback }: { feedback: React.ReactNode }) {
  // Links like "Guide on settling up" jump to a section; the page mounts after sign-in loads,
  // so scroll once it's on screen.
  const hash = useRouterState({ select: (st) => st.location.hash });
  useEffect(() => {
    if (!hash) return;
    const id = requestAnimationFrame(() =>
      document.getElementById(hash)?.scrollIntoView({ block: "start", behavior: "smooth" }),
    );
    return () => cancelAnimationFrame(id);
  }, [hash]);
  return (
    <div className="mx-auto max-w-5xl space-y-14 px-4 py-6 md:px-8 md:py-10">
      <div className="space-y-4">
        <PageHeader eyebrow="No house. No vig." title="How to play" />
        <nav aria-label="Guide sections" className="flex flex-wrap gap-2">
          {SECTIONS.map((x) => (
            <Link
              key={x.id}
              to="/how-to-play"
              hash={x.id}
              className="rounded-full border bg-surface px-3 py-1.5 text-xs font-semibold transition-colors hover:border-primary/60 hover:text-primary"
            >
              {x.label}
            </Link>
          ))}
        </nav>
      </div>

      {/* Lifecycle, with the payout example as the last step's explainer */}
      <section id="lifecycle" className="scroll-mt-20 space-y-5">
        <h2 className="text-4xl font-black uppercase">Bet lifecycle</h2>
        <ol className="grid gap-3 md:grid-cols-4">
          {LIFECYCLE.map((s, i) => (
            <li key={s.status} className="rounded-xl border bg-surface p-5">
              <div className="flex items-center justify-between">
                <StatusBadge status={s.status} />
                <span className="scoreboard text-3xl text-muted-foreground/40">0{i + 1}</span>
              </div>
              <p className="mt-4 text-sm leading-relaxed text-muted-foreground">{s.text}</p>
            </li>
          ))}
        </ol>

        <div className="grid gap-6 rounded-2xl border bg-surface p-6 md:grid-cols-[1fr_auto_1fr] md:items-center md:p-8">
          <div>
            <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
              Pooled payout
            </p>
            <h3 className="mt-2 text-4xl font-black uppercase leading-none">The losers' pot</h3>
            <p className="mt-3 text-sm text-muted-foreground">
              Three friends wager 1 point each. Two take the Patriots, one takes the Bills.
            </p>
          </div>
          <ArrowRight className="hidden size-6 text-muted-foreground md:block" />
          <div className="grid gap-2">
            <Outcome win="Patriots win" result="Each Patriots fan gets +0.5" />
            <Outcome win="Bills win" result="The Bills fan takes +2" />
          </div>
        </div>
      </section>

      {/* Modes */}
      <ModesSection />

      {/* Strategy */}
      <section id="strategy" className="scroll-mt-20 ticket p-6 [--notch-y:50%] md:p-8">
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-ticket-muted">
          Strategy
        </p>
        <h2 className="mt-2 text-4xl font-black uppercase leading-none">Fade the public</h2>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed">
          The fewer people on your side, the bigger your share. If ten friends take the safe pick
          and you're right too, you split one loser's wager. Winning isn't enough, you have to win
          when the table loses.
        </p>
      </section>

      <SocialSection />

      <SettlingUpSection />
      <QuickPicksGuide />

      <SafetySection />

      {feedback}

      <MoneySection />
    </div>
  );
}

function SocialSection() {
  return (
    <section id="home-game" className="scroll-mt-20 space-y-5">
      <div>
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
          Social gaming
        </p>
        <h2 className="mt-1 text-4xl font-black uppercase leading-none">Like a home game</h2>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-muted-foreground">
          P2Picks works like a friendly poker night or office pool: no house, no rake, and every
          point goes back to the players. Rules on social betting vary by state, and a few states
          ban it entirely, so check where you live.
        </p>
      </div>
      <div className="grid gap-3 md:grid-cols-3">
        {SOCIAL_POINTS.map(({ icon: Icon, title, text }) => (
          <div key={title} className="rounded-xl border bg-surface p-5">
            <div className="flex items-center gap-3">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-primary">
                <Icon className="size-4" />
              </span>
              <h3 className="text-2xl font-extrabold uppercase leading-none">{title}</h3>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{text}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

function SettlingUpSection() {
  return (
    <section id="settling-up" className="scroll-mt-20 space-y-5">
      <div>
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
          Settle-ups
        </p>
        <h2 className="mt-1 text-4xl font-black uppercase leading-none">Settling up</h2>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-muted-foreground">
          If someone won't pay, that's between you and them. Talk it out, bring in the host, and
          stop betting with people who don't settle. Only report it if it turns into harassment or
          pressure.
        </p>
      </div>
      <IconCards items={SETTLE_POINTS} />
    </section>
  );
}

function QuickPicksGuide() {
  return (
    <section id="quick-picks" className="scroll-mt-20 space-y-5">
      <div>
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
          Advanced, for hosts
        </p>
        <h2 className="mt-1 text-4xl font-black uppercase leading-none">Quick Picks</h2>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-muted-foreground">
          Some groups just want to bet without dreaming up good bets. With Quick Picks, a table can
          get another table's bets automatically. Every table still only bets among itself: your pot
          is your table, and points never move between tables.
        </p>
      </div>
      <IconCards items={QUICK_PICK_POINTS} />
      <div className="flex gap-4 rounded-xl border bg-surface p-5">
        <Megaphone className="size-5 shrink-0 text-primary" />
        <div className="space-y-1">
          <h3 className="text-xl font-extrabold uppercase leading-none">
            Stadiums and live events
          </h3>
          <p className="text-sm leading-relaxed text-muted-foreground">
            A stadium crew, a bar or a stream host can share U2Picks for things no sports feed
            tracks, like "T-shirt cannon reaches the upper deck?". They call the result on their
            end, and friend groups compete just among themselves.
          </p>
        </div>
      </div>
      <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
        Hosts can unsubscribe anytime, and tickets already posted stay. A sharing host can make a
        new code to cut off everyone using the old one. Copies can't be shared again, and a table
        with U2Pick turned off doesn't get U2Pick copies. Subscribing is free.
      </p>
    </section>
  );
}

function SafetySection() {
  // TODO: link the Terms and Conditions here once written; they must repeat the deletion and reports rules.
  return (
    <section id="safety" className="scroll-mt-20 space-y-5">
      <div>
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
          Your account
        </p>
        <h2 className="mt-1 text-4xl font-black uppercase leading-none">Safety and reports</h2>
        <p className="mt-3 max-w-2xl rounded-lg border-l-2 border-primary bg-surface px-4 py-3 text-sm leading-relaxed">
          <span className="font-semibold">How reports are handled:</span> a person on the P2Picks
          team reviews every report. Depending on what happened we take no action, warn the person,
          or remove their account. You can follow your report's status in Preferences, Safety.
        </p>
      </div>
      <IconCards items={SAFETY_POINTS} />
    </section>
  );
}

function IconCards({ items }: { items: { icon: typeof Flag; title: string; text: string }[] }) {
  return (
    <div className="grid gap-3 md:grid-cols-3">
      {items.map(({ icon: Icon, title, text }) => (
        <div key={title} className="rounded-xl border bg-surface p-5">
          <div className="flex items-center gap-3">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-primary">
              <Icon className="size-4" />
            </span>
            <h3 className="text-2xl font-extrabold uppercase leading-none">{title}</h3>
          </div>
          <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{text}</p>
        </div>
      ))}
    </div>
  );
}

function MoneySection() {
  const [patreonOpen, setPatreonOpen] = useState(false);
  return (
    <section id="money" className="scroll-mt-20 space-y-5">
      <div>
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
          Profit-free
        </p>
        <h2 className="mt-1 text-4xl font-black uppercase leading-none">How we make money</h2>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-muted-foreground">
          We don't make money from your bets, and we never will. P2Picks is free. It's kept running
          by people who choose to chip in.
        </p>
        <p className="mt-3 max-w-2xl rounded-lg border-l-2 border-primary bg-surface px-4 py-3 text-sm font-semibold leading-relaxed">
          Our promise: any profit from betting would make P2Picks worse for you, so we won't chase
          it. Hold us to that.
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-3">
        {MONEY_POINTS.map(({ icon: Icon, title, text }) => (
          <div key={title} className="rounded-xl border bg-surface p-5">
            <div className="flex items-center gap-3">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-primary">
                <Icon className="size-4" />
              </span>
              <h3 className="text-2xl font-extrabold uppercase leading-none">{title}</h3>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{text}</p>
          </div>
        ))}
      </div>

      <div className="flex flex-col gap-4 rounded-xl border bg-surface p-5 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h3 className="text-2xl font-extrabold uppercase leading-none">Support P2Picks</h3>
          <p className="mt-1 text-sm text-muted-foreground">
            Like it? You can help cover hosting costs. Any supporter perks will only ever be extras
            outside of betting. Nobody, us included, ever gets an edge on a ticket.
          </p>
        </div>
        {/* TODO: link to the Patreon page once it exists (replace this popup with an external link). */}
        <Button type="button" onClick={() => setPatreonOpen(true)} className="shrink-0">
          <Heart className="size-4" />
          Support on Patreon
        </Button>
      </div>

      <div className="flex gap-4 rounded-xl border bg-surface p-5">
        <Zap className="size-5 shrink-0 text-primary" />
        <div className="space-y-1">
          <h3 className="text-xl font-extrabold uppercase leading-none">
            An idea: paid Quick Picks feeds
          </h3>
          <p className="text-sm leading-relaxed text-muted-foreground">
            Subscribing to a{" "}
            <Link
              to="/how-to-play"
              hash="quick-picks"
              className="text-primary underline underline-offset-2"
            >
              Quick Picks
            </Link>{" "}
            feed is free today. Later, hosts who make great bets might choose to charge other hosts
            a subscription, with part of it (say half) helping pay to keep P2Picks running. It would
            pay for the bets someone writes, never for anything inside a ticket: no cut of any pot,
            no better odds, and the same rules for every table.
          </p>
        </div>
      </div>

      <div className="flex gap-4 rounded-xl border border-dashed p-5">
        <HeartHandshake className="size-5 shrink-0 text-muted-foreground" />
        <p className="text-sm text-muted-foreground">
          Points are mock dollars. P2Picks keeps the ledger but doesn't hold money or set limits.
          Play with people you trust, and take a break when it stops being fun.
        </p>
      </div>

      <Dialog open={patreonOpen} onOpenChange={setPatreonOpen}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Coming soon</DialogTitle>
            <DialogDescription>Patreon for your support coming soon.</DialogDescription>
          </DialogHeader>
          <Button type="button" variant="secondary" onClick={() => setPatreonOpen(false)}>
            Got it
          </Button>
        </DialogContent>
      </Dialog>
    </section>
  );
}

function Outcome({ win, result }: { win: string; result: string }) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-lg bg-surface-raised px-4 py-3">
      <span className="font-display text-lg font-bold uppercase">{win}</span>
      <span className="text-sm text-primary">{result}</span>
    </div>
  );
}
