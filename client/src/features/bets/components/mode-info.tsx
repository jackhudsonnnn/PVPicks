import { Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { ScrollContainer } from "@/components/scroll-container";
import { cn } from "@/lib/utils";
import type { ModeDef } from "@/features/bets/modes";
import { MODE_INFO } from "@/features/bets/mode-info";
import { Section } from "./form-parts";

/**
 * A mode's info: its description and how it works, the rules worth knowing, and worked examples. The proposal sheet
 * shows it in place of the setup (the (i) beside the mode's name); the Guide opens it in a dialog
 * (`ModeInfoButton`). One source, `MODE_INFO`.
 */
export function ModeInfo({ mode }: { mode: ModeDef }) {
  const info = MODE_INFO[mode.id];
  return (
    <div className="space-y-6">
      {info.comingSoon && (
        <p className="rounded-lg border border-status-pending/40 bg-status-pending/10 px-3 py-2 text-xs text-foreground">
          {info.comingSoon}
        </p>
      )}
      <Section label="How it works">
        <div className="space-y-2 text-sm text-muted-foreground">
          <p className="text-foreground">{mode.description}</p>
          {info.how.map((paragraph) => (
            <p key={paragraph}>{paragraph}</p>
          ))}
        </div>
      </Section>
      <Section label="Examples">
        <ol className="grid gap-3">
          {info.examples.map((example, i) => (
            <li key={example.title} className="space-y-2 rounded-xl border bg-surface p-3">
              <p className="flex items-baseline gap-2">
                <span className="font-mono text-[10px] font-bold text-muted-foreground">
                  {i + 1}
                </span>
                <span className="font-semibold">{example.title}</span>
              </p>
              <ul className="flex flex-wrap gap-1" aria-label="Settings">
                {example.setup.map((item) => (
                  <li
                    key={item}
                    className="rounded-md bg-muted px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-wider text-muted-foreground"
                  >
                    {item}
                  </li>
                ))}
              </ul>
              <p className="text-sm text-muted-foreground">{example.story}</p>
              <p
                className={cn(
                  "border-l-2 pl-2 text-sm font-medium",
                  example.wash ? "border-muted-foreground/50" : "border-primary",
                )}
              >
                {example.result}
              </p>
            </li>
          ))}
        </ol>
      </Section>
      <Section label="Good to know">
        <ul className="list-disc space-y-1.5 pl-4 text-xs text-muted-foreground marker:text-muted-foreground/60">
          {info.notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      </Section>
    </div>
  );
}

/** The (i) that opens a mode's info in a dialog (the Guide's mode cards). */
export function ModeInfoButton({ mode, className }: { mode: ModeDef; className?: string }) {
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className={cn("size-8 text-muted-foreground", className)}
          aria-label={`How ${mode.name} works`}
        >
          <Info />
        </Button>
      </DialogTrigger>
      <DialogContent className="flex max-h-[85dvh] max-w-md flex-col gap-4 p-0">
        <DialogHeader className="px-5 pt-5 text-left">
          <DialogTitle className="font-display text-2xl font-extrabold uppercase">
            {mode.name}
          </DialogTitle>
          <DialogDescription className="font-mono text-[11px] uppercase tracking-wider text-primary">
            {mode.tagline}
          </DialogDescription>
        </DialogHeader>
        <ScrollContainer isVisible={false} className="min-h-0 flex-1 px-5 pb-5">
          <ModeInfo mode={mode} />
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}
