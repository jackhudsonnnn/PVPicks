/** The chat input: text, a GIF picker that sends on tap, and the poll button. */
import { useState } from "react";
import { MessageSquareOff, Search, SendHorizontal } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { searchGifs, useGifs } from "./gifs";
import { textProblem } from "./model";
import { LoadingList } from "@/components/loading";
import { PollDialog } from "./poll-dialog";

export function Composer({
  canText,
  canGif,
  canPoll,
  blocked,
  sent,
  send,
  startPoll,
}: {
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
          <GifPicker disabled={blocked} onPick={(gifId) => send({ kind: "gif", gifId })} />
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

/** "GIF" button next to the input; picking one sends it straight away. */
function GifPicker({ disabled, onPick }: { disabled: boolean; onPick: (gifId: string) => void }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const catalog = useGifs();
  const [picked, setCategory] = useState<string | null>(null);
  const category = picked ?? catalog.data?.categories[0]?.id ?? "";
  const results = catalog.data ? searchGifs(catalog.data, search, category) : [];
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Choose a GIF"
          disabled={disabled}
          className="flex h-11 shrink-0 items-center rounded-md border bg-surface px-2.5 font-mono text-[11px] font-black tracking-wider text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground disabled:opacity-50"
        >
          GIF
        </button>
      </PopoverTrigger>
      <PopoverContent
        side="top"
        align="start"
        className="w-[min(22rem,calc(100vw-1.5rem))] space-y-2 bg-sidebar p-2 outline-none"
        // Focus the panel, not the search box: a focused input opens the phone keyboard over the GIFs.
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          (event.currentTarget as HTMLElement | null)?.focus();
        }}
      >
        <div className="relative">
          <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search GIFs"
            className="h-10 bg-surface pl-9"
            aria-label="Search GIFs"
          />
        </div>
        {!search.trim() && (
          <SegmentedTabs
            size="sm"
            ariaLabel="GIF category"
            tabs={catalog.data?.categories ?? []}
            value={category}
            onChange={setCategory}
            className="bg-surface p-1"
          />
        )}
        <ScrollContainer
          isVisible={false}
          className="grid h-64 auto-rows-max grid-cols-2 content-start gap-1.5"
        >
          {catalog.isPending ? (
            <LoadingList label="Loading GIFs" rows={3} rowClassName="h-24" className="col-span-2" />
          ) : catalog.isError ? (
            <p role="alert" className="col-span-2 py-10 text-center text-sm">
              GIFs are unavailable right now.
            </p>
          ) : (
            results.length === 0 && (
              <p className="col-span-2 py-10 text-center text-sm text-muted-foreground">
                No GIFs for “{search}”.
              </p>
            )
          )}
          {results.map((gif) => (
            <button
              key={gif.id}
              type="button"
              aria-label={`Send GIF: ${gif.alt}`}
              onClick={() => {
                onPick(gif.id);
                setOpen(false);
                setSearch("");
              }}
              className="overflow-hidden rounded-lg bg-muted ring-primary transition hover:ring-2"
            >
              <img
                src={gif.url}
                alt=""
                loading="lazy"
                className="aspect-square w-full object-cover"
              />
            </button>
          ))}
        </ScrollContainer>
      </PopoverContent>
    </Popover>
  );
}
