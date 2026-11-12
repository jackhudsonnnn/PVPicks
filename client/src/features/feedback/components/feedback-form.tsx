import { useState } from "react";
import { Check, Lightbulb, SendHorizontal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { cn } from "@/lib/utils";
import { useAuth } from "@/features/auth/auth-provider";
import { FEEDBACK_KINDS, FEEDBACK_MAX, feedbackProblem, type FeedbackKind } from "../feedback";
import { useFeedback } from "../feedback-storage";

/** Guide section: send game mode ideas, perk ideas, GIFs, bugs or anything else. */
export function FeedbackSection() {
  const { account } = useAuth();
  const { send } = useFeedback();
  const [kind, setKind] = useState<FeedbackKind>("mode");
  const [text, setText] = useState("");
  const [link, setLink] = useState("");
  const [sent, setSent] = useState(false);
  const meta = FEEDBACK_KINDS.find((k) => k.id === kind)!;
  const problem = feedbackProblem(text, link);

  return (
    <section id="feedback" className="scroll-mt-20 space-y-5">
      <div>
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
          Help build it
        </p>
        <h2 className="mt-1 text-4xl font-black uppercase leading-none">Feedback</h2>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-muted-foreground">
          Got a bet your table keeps making by hand, a GIF the picker is missing, or a bug? Tell us.
          Every message is read by the team.
        </p>
      </div>

      <div className="space-y-3 rounded-xl border bg-surface p-5">
        {sent ? (
          <div className="flex flex-col items-start gap-3">
            <p className="flex items-center gap-2 font-semibold">
              <Check className="size-4 text-primary" /> Thanks, it's in.
            </p>
            <p className="text-sm text-muted-foreground">
              We read everything. Good ideas show up in the app.
            </p>
            <Button variant="outline" size="sm" onClick={() => setSent(false)}>
              Send more
            </Button>
          </div>
        ) : (
          <>
            <SegmentedTabs<FeedbackKind>
              size="sm"
              ariaLabel="Feedback type"
              tabs={FEEDBACK_KINDS.map((k) => ({ id: k.id, label: k.label }))}
              value={kind}
              onChange={setKind}
            />
            <p className="flex gap-2 text-xs text-muted-foreground">
              <Lightbulb className="mt-0.5 size-3.5 shrink-0" /> {meta.hint}
            </p>
            <Textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={meta.placeholder}
              aria-label="Your feedback"
              maxLength={FEEDBACK_MAX + 50}
              className="min-h-28 bg-background"
            />
            {(kind === "gif" || kind === "bug") && (
              <Input
                value={link}
                onChange={(e) => setLink(e.target.value)}
                placeholder={kind === "gif" ? "GIF link (optional)" : "Screenshot link (optional)"}
                aria-label="Link"
                className="h-10 bg-background"
              />
            )}
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p
                className={cn(
                  "text-xs",
                  text.length > 0 && problem ? "text-loss" : "text-muted-foreground",
                )}
              >
                {text.length > 0 && problem ? problem : `${text.length}/${FEEDBACK_MAX}`}
              </p>
              <Button
                disabled={!!problem}
                onClick={() => {
                  send({
                    kind,
                    text: text.trim(),
                    link: link.trim() || undefined,
                    account: account ?? undefined,
                  });
                  setText("");
                  setLink("");
                  setSent(true);
                }}
              >
                <SendHorizontal /> Send feedback
              </Button>
            </div>
          </>
        )}
      </div>
    </section>
  );
}
