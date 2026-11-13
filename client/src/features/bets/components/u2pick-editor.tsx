import { useRef, useState } from "react";
import { Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SegmentedTabs } from "@/components/segmented-tabs";
import type { SettlerRule } from "../types";
import { MAX_OPTIONS, SETTLER_RULES } from "../u2pick";
import { Section } from "./form-parts";

/** U2Pick (custom) bet: the question, 2–6 options and who calls the result. */
export function U2PickEditor({
  question,
  onQuestionChange,
  options,
  onOptionsChange,
  settledBy,
  onSettledByChange,
}: {
  question: string;
  onQuestionChange: (q: string) => void;
  options: string[];
  onOptionsChange: (options: string[]) => void;
  settledBy: SettlerRule;
  onSettledByChange: (rule: SettlerRule) => void;
}) {
  // Each option row keeps its own key, so removing one doesn't move typing focus to another.
  const next = useRef(options.length);
  const [keys, setKeys] = useState(() => options.map((_, i) => i));
  const rowKeys = keys.length === options.length ? keys : options.map((_, i) => i);

  const remove = (i: number) => {
    onOptionsChange(options.filter((_, j) => j !== i));
    setKeys(rowKeys.filter((_, j) => j !== i));
  };
  const add = () => {
    onOptionsChange([...options, ""]);
    setKeys([...rowKeys, next.current++]);
  };

  return (
    <>
      <Section label="What's the bet?">
        <Input
          value={question}
          onChange={(e) => onQuestionChange(e.target.value)}
          placeholder="Jack to spill beer on his shirt tonight?"
          aria-label="Bet question"
          className="h-11 bg-surface"
        />
      </Section>
      <Section label={`Options (2–${MAX_OPTIONS})`}>
        <div className="grid gap-2">
          {options.map((o, i) => (
            <div key={rowKeys[i]} className="flex items-center gap-2">
              <Input
                value={o}
                onChange={(e) =>
                  onOptionsChange(options.map((x, j) => (j === i ? e.target.value : x)))
                }
                placeholder={`Option ${i + 1}`}
                aria-label={`Option ${i + 1}`}
                className="h-10 bg-surface"
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="size-9 shrink-0 text-muted-foreground"
                disabled={options.length <= 2}
                onClick={() => remove(i)}
                aria-label={`Remove option ${i + 1}`}
              >
                <X />
              </Button>
            </div>
          ))}
          {options.length < MAX_OPTIONS && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="justify-self-start"
              onClick={add}
            >
              <Plus /> Add option
            </Button>
          )}
        </div>
      </Section>
      <Section label="Who calls the result?">
        <SegmentedTabs<SettlerRule>
          size="sm"
          ariaLabel="Who calls the result"
          narrowColumns={2}
          tabs={SETTLER_RULES.map((r) => ({ id: r.id, label: r.label }))}
          value={settledBy}
          onChange={onSettledByChange}
        />
        <p className="text-xs text-muted-foreground">
          {SETTLER_RULES.find((r) => r.id === settledBy)!.description}
        </p>
      </Section>
    </>
  );
}
