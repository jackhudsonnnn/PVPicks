import { useState } from "react";
import { Search, SmilePlus } from "lucide-react";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import { GIF_CATEGORIES, searchGifs, type GifCategory } from "../gifs";
import { QUICK_REACTIONS, summarize } from "../reactions";
import { hasPermission } from "../hosts";

/** "GIF" button next to the chat input; picking one sends it straight away. */
export function GifPicker({ onPick }: { onPick: (gifId: string) => void }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [cat, setCat] = useState<GifCategory>("hype");
  const results = searchGifs(q, cat);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Send a GIF"
          className="flex h-11 shrink-0 items-center rounded-md border bg-surface px-2.5 font-mono text-[11px] font-black tracking-wider text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground"
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
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search GIFs"
            className="h-10 bg-surface pl-9"
            aria-label="Search GIFs"
          />
        </div>
        {!q.trim() && (
          <SegmentedTabs
            size="sm"
            ariaLabel="GIF categories"
            tabs={GIF_CATEGORIES}
            value={cat}
            onChange={setCat}
            className="bg-surface p-1"
          />
        )}
        <ScrollContainer isVisible={false} className="grid h-64 grid-cols-2 content-start gap-1.5">
          {results.length === 0 && (
            <p className="col-span-2 py-10 text-center text-sm text-muted-foreground">
              No GIFs for “{q}”.
            </p>
          )}
          {results.map((g) => (
            <button
              key={g.id}
              type="button"
              onClick={() => {
                onPick(g.id);
                setOpen(false);
                setQ("");
              }}
              className="overflow-hidden rounded-lg bg-muted ring-primary transition hover:ring-2"
            >
              <img
                src={g.url}
                alt={g.alt}
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

/** Can you react in the chat this message belongs to? Hosts can turn reactions off per member. */
function useCanReact(messageId: string) {
  const me = useMe();
  const { state } = useAppStore();
  const m = state.messages.find((x) => x.id === messageId);
  const t = m && state.tables.find((x) => x.id === m.tableId);
  return !!t && hasPermission(t, me, "react");
}

/** Small smiley button that opens the quick-reaction bar for a message. */
export function ReactButton({ messageId, className }: { messageId: string; className?: string }) {
  const me = useMe();
  const { state, actions } = useAppStore();
  const current = summarize(state.reactions[messageId], me).find((c) => c.mine)?.emoji;
  const [open, setOpen] = useState(false);
  const canReact = useCanReact(messageId);
  if (!canReact) return null;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="React"
          className={cn(
            "flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground/60 transition-colors hover:bg-surface-raised hover:text-foreground data-[state=open]:bg-surface-raised data-[state=open]:text-foreground",
            className,
          )}
        >
          <SmilePlus className="size-4" />
        </button>
      </PopoverTrigger>
      <PopoverContent side="top" className="flex w-auto gap-0.5 rounded-full bg-sidebar p-1">
        {QUICK_REACTIONS.map((e) => (
          <button
            key={e}
            type="button"
            onClick={() => {
              actions.react(messageId, e);
              setOpen(false);
            }}
            className={cn(
              "flex size-9 items-center justify-center rounded-full text-xl transition-transform hover:scale-125 hover:bg-surface-raised",
              current === e && "bg-primary/25 ring-1 ring-primary",
            )}
            aria-label={`React ${e}`}
            aria-pressed={current === e}
          >
            {e}
          </button>
        ))}
      </PopoverContent>
    </Popover>
  );
}

/** Reaction chips under a message; tap one to add or remove your own. */
export function ReactionChips({ messageId, className }: { messageId: string; className?: string }) {
  const me = useMe();
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const chips = summarize(state.reactions[messageId], me);
  const canReact = useCanReact(messageId);
  if (!chips.length) return null;
  return (
    <div className={cn("mt-1 flex flex-wrap gap-1", className)}>
      {chips.map((c) => (
        <button
          key={c.emoji}
          type="button"
          disabled={!canReact}
          onClick={() => actions.react(messageId, c.emoji)}
          title={c.userIds
            .map((id) => (id === me ? "You" : getUser(id).username.split(" ")[0]))
            .join(", ")}
          className={cn(
            "flex animate-rise items-center gap-1 rounded-full border px-1.5 py-0.5 text-xs transition-colors disabled:cursor-default",
            c.mine
              ? "border-primary/70 bg-primary/15 text-foreground"
              : "bg-surface text-muted-foreground hover:border-primary/40",
          )}
          aria-label={`${c.emoji} ${c.count}${c.mine ? ", including you" : ""}`}
        >
          <span>{c.emoji}</span>
          <span className="font-mono text-[10px] font-bold">{c.count}</span>
        </button>
      ))}
    </div>
  );
}
