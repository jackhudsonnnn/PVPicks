/** The "Start a poll" dialog behind the composer's poll button. */
import { useState } from "react";
import { ChartBar, Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { POLL_MAX_OPTIONS, POLL_OPTION_LIMIT, POLL_QUESTION_LIMIT, pollDraft } from "./model";

export function PollDialog({
  disabled,
  onCreate,
}: {
  disabled: boolean;
  onCreate: (poll: { question: string; options: string[] }) => void;
}) {
  const [open, setOpen] = useState(false);
  const [question, setQuestion] = useState("");
  const [options, setOptions] = useState(["", ""]);
  const [problem, setProblem] = useState<string | null>(null);

  function reset(next: boolean) {
    setOpen(next);
    if (!next) {
      setQuestion("");
      setOptions(["", ""]);
      setProblem(null);
    }
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    // The dialog renders inside the composer's form in React's tree, so its submit would bubble there too.
    event.stopPropagation();
    const draft = pollDraft(question, options);
    if ("problem" in draft) {
      setProblem(draft.problem);
      return;
    }
    onCreate(draft);
    reset(false);
  }

  return (
    <Dialog open={open} onOpenChange={reset}>
      <DialogTrigger asChild>
        <button
          type="button"
          aria-label="Start a poll"
          disabled={disabled}
          className="flex size-11 shrink-0 items-center justify-center rounded-md border bg-surface text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground disabled:opacity-50"
        >
          <ChartBar className="size-4" />
        </button>
      </DialogTrigger>
      <DialogContent className="max-w-md">
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>Start a poll</DialogTitle>
            <DialogDescription>
              Everyone at the table can vote once and change their vote any time. Votes show who
              picked what.
            </DialogDescription>
          </DialogHeader>
          <Input
            aria-label="Question"
            placeholder="Ask a question"
            value={question}
            maxLength={POLL_QUESTION_LIMIT * 2}
            onChange={(event) => setQuestion(event.target.value)}
            className="h-11 bg-surface"
          />
          <ol className="space-y-2">
            {options.map((option, index) => (
              <li key={index} className="flex items-center gap-2">
                <Input
                  aria-label={`Option ${index + 1}`}
                  placeholder={`Option ${index + 1}`}
                  value={option}
                  maxLength={POLL_OPTION_LIMIT * 2}
                  onChange={(event) =>
                    setOptions(options.map((o, i) => (i === index ? event.target.value : o)))
                  }
                  className="h-10 bg-surface"
                />
                {options.length > 2 && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label={`Remove option ${index + 1}`}
                    onClick={() => setOptions(options.filter((_, i) => i !== index))}
                  >
                    <X />
                  </Button>
                )}
              </li>
            ))}
          </ol>
          {options.length < POLL_MAX_OPTIONS && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setOptions([...options, ""])}
            >
              <Plus /> Add option
            </Button>
          )}
          {problem && (
            <p role="alert" className="text-xs text-loss">
              {problem}
            </p>
          )}
          <DialogFooter>
            <Button type="submit">Post poll</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
