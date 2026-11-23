import { useState } from "react";
import { Search, Star } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { LoadingList } from "@/components/loading";
import { cn } from "@/lib/utils";
import { STARRED, searchGifs, starredGifs, useGifFavorites, useGifs } from "./gifs";

/**
 * The "GIF" button next to the chat input and its picker, a modal like the poll composer: search, your starred GIFs
 * first, then the library's categories. Tapping a GIF sends it; the star on each one adds it to (or takes it out of)
 * your Starred tab.
 */
export function GifDialog({
  userId,
  disabled,
  onPick,
}: {
  userId: string;
  disabled: boolean;
  onPick: (gifId: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const catalog = useGifs();
  const favorites = useGifFavorites(userId);
  const [picked, setCategory] = useState<string | null>(null);
  // Starred first when you have any, otherwise the first category.
  const category =
    picked ?? (favorites.ids.length > 0 ? STARRED : (catalog.data?.categories[0]?.id ?? ""));
  const results = !catalog.data
    ? []
    : !search.trim() && category === STARRED
      ? starredGifs(catalog.data, favorites.ids)
      : searchGifs(catalog.data, search, category);
  const tabs = [{ id: STARRED, label: "★ Starred" }, ...(catalog.data?.categories ?? [])];
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setSearch("");
      }}
    >
      <DialogTrigger asChild>
        <button
          type="button"
          aria-label="Choose a GIF"
          disabled={disabled}
          className="flex h-11 shrink-0 items-center rounded-md border bg-surface px-2.5 font-mono text-[11px] font-black tracking-wider text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground disabled:opacity-50"
        >
          GIF
        </button>
      </DialogTrigger>
      <DialogContent
        className="flex max-h-[85dvh] max-w-md flex-col gap-3"
        // Focus the dialog, not the search box: a focused input opens the phone keyboard over the GIFs.
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          (event.currentTarget as HTMLElement | null)?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>Send a GIF</DialogTitle>
          <DialogDescription>Tap one to send it. Star the ones you use most.</DialogDescription>
        </DialogHeader>
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
            tabs={tabs}
            value={category}
            onChange={setCategory}
            className="bg-surface p-1"
          />
        )}
        <ScrollContainer
          isVisible={false}
          className="grid min-h-64 auto-rows-max grid-cols-2 content-start gap-1.5"
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
                {search.trim()
                  ? `No GIFs for “${search}”.`
                  : "Nothing starred yet. Tap the star on a GIF to keep it here."}
              </p>
            )
          )}
          {results.map((gif) => {
            const starred = favorites.isStarred(gif.id);
            return (
              <div key={gif.id} className="relative">
                <button
                  type="button"
                  aria-label={`Send GIF: ${gif.alt}`}
                  onClick={() => {
                    onPick(gif.id);
                    setOpen(false);
                    setSearch("");
                  }}
                  className="block w-full overflow-hidden rounded-lg bg-muted ring-primary transition hover:ring-2"
                >
                  <img
                    src={gif.url}
                    alt=""
                    loading="lazy"
                    className="aspect-square w-full object-cover"
                  />
                </button>
                <button
                  type="button"
                  aria-label={starred ? `Unstar ${gif.alt}` : `Star ${gif.alt}`}
                  aria-pressed={starred}
                  onClick={() => favorites.toggle(gif.id)}
                  className="absolute right-1 top-1 flex size-8 items-center justify-center rounded-full bg-black/55 text-white transition-colors hover:bg-black/75"
                >
                  <Star className={cn("size-4", starred && "fill-primary text-primary")} />
                </button>
              </div>
            );
          })}
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}
