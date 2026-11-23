/** The chat input: text, the GIF picker (a modal that sends on tap), and the poll button. */
import { useState } from "react";
import { MessageSquareOff, SendHorizontal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { textProblem } from "./model";
import { PollDialog } from "./poll-dialog";
import { GifDialog } from "./gif-dialog";

export function Composer({
  userId,
  canText,
  canGif,
  canPoll,
  blocked,
  sent,
  send,
  startPoll,
}: {
  /** Whose starred GIFs the picker shows. */
  userId: string;
  canText: boolean;
  canGif: boolean;
  /** Whether the table has polls on and you have the poll permission. */
  canPoll: boolean;
  blocked: boolean;
  /** The last chat action went through; the draft clears only then, so a refused message can be edited. */
  sent: boolean;
  send: (message: { kind: "text"; text: string } | { kind: "gif"; gifId: string }) => void;
  startPoll: (poll: { question: string; options: string[] }) => void;
}) {
  const [text, setText] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  // The text being sent: cleared from the box once the send succeeds, kept if it's refused.
  const [sending, setSending] = useState<string | null>(null);
  if (sending !== null && !blocked) {
    setSending(null);
    if (sent && text.trim() === sending) setText("");
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = textProblem(text);
    setProblem(invalid);
    if (invalid) return;
    setSending(text.trim());
    send({ kind: "text", text: text.trim() });
  }

  if (!canText && !canGif && !canPoll) {
    return (
      <p className="flex items-center justify-center gap-2 border-t bg-sidebar p-4 text-center text-xs text-muted-foreground">
        <MessageSquareOff className="size-4 shrink-0" /> A host turned off chat for you at this
        table.
      </p>
    );
  }
  return (
    <div className="border-t bg-sidebar p-3">
      {problem && (
        <p role="alert" className="mb-2 text-xs text-loss">
          {problem}
        </p>
      )}
      <form onSubmit={submit} className="flex items-center gap-2">
        {canGif && (
          <GifDialog
            userId={userId}
            disabled={blocked}
            onPick={(gifId) => send({ kind: "gif", gifId })}
          />
        )}
        {canPoll && <PollDialog disabled={blocked} onCreate={startPoll} />}
        <Input
          aria-label="Message"
          placeholder={canText ? "Talk trash…" : "A host turned off texts for you"}
          value={text}
          disabled={!canText || blocked}
          onChange={(event) => setText(event.target.value)}
          className="h-11 bg-surface"
        />
        <Button
          type="submit"
          size="icon"
          className="size-11 shrink-0"
          aria-label="Send"
          disabled={!canText || blocked}
        >
          <SendHorizontal />
        </Button>
      </form>
    </div>
  );
}
