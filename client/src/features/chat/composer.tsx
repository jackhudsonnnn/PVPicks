/** The chat input: text, and a GIF picker that sends on tap. */
import { useState } from "react";
import { MessageSquareOff, Search, SendHorizontal } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { searchGifs, useGifs } from "./gifs";
import { textProblem } from "./model";

export function Composer({
  canText,
  canGif,
  blocked,
  send,
}: {
  canText: boolean;
  canGif: boolean;
  blocked: boolean;
  send: (message: { kind: "text"; text: string } | { kind: "gif"; gifId: string }) => void;
}) {
  const [text, setText] = useState("");
  const [problem, setProblem] = useState<string | null>(null);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = textProblem(text);
    setProblem(invalid);
    if (invalid) return;
    send({ kind: "text", text: text.trim() });
    setText("");
  }

  if (!canText && !canGif) {
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
        className="w-[min(22rem,calc(100vw-1.5rem))] space-y-2 bg-sidebar p-2"
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
        <ScrollContainer isVisible={false} className="grid h-64 grid-cols-2 content-start gap-1.5">
          {catalog.isPending ? (
            <p className="col-span-2 py-10 text-center text-sm text-muted-foreground">
              Loading GIFs…
            </p>
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
