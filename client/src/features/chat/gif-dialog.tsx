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
import { LoadingList } from "@/components/loading";
import { cn } from "@/lib/utils";
import { favoriteGifs, searchGifs, useGifFavorites, useGifs } from "./gifs";

/**
 * The "GIF" button next to the chat input and its picker, a modal like the poll composer: one search over the whole
 * library, and a star beside it that narrows the grid (and the search) to your favorites. Tapping a GIF sends it; the
 * star on each one adds it to (or takes it out of) your favorites.
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
  const [onlyFavorites, setOnlyFavorites] = useState(false);
  const results = !catalog.data
    ? []
    : searchGifs(
        catalog.data,
        search,
        onlyFavorites ? favoriteGifs(catalog.data, favorites.ids) : catalog.data.gifs,
      );
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setSearch("");
          setOnlyFavorites(false);
        }
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
        // One fixed height, so switching to favorites (or a narrow search) never resizes the modal.
        className="flex h-[min(85dvh,28rem)] max-w-md flex-col gap-3"
        // Focus the dialog, not the search box: a focused input opens the phone keyboard over the GIFs.
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          (event.currentTarget as HTMLElement | null)?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>Send a GIF</DialogTitle>
          <DialogDescription>
            Tap one to send it. Star the ones you use most to keep them in your favorites.
          </DialogDescription>
        </DialogHeader>
        <div className="flex gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={onlyFavorites ? "Search favorites" : "Search GIFs"}
              className="h-10 bg-surface pl-9"
              aria-label="Search GIFs"
            />
          </div>
          <button
            type="button"
            aria-label="Favorites"
            aria-pressed={onlyFavorites}
            title="Favorites"
            onClick={() => setOnlyFavorites((on) => !on)}
            className={cn(
              "flex size-10 shrink-0 items-center justify-center rounded-md border transition-colors",
              onlyFavorites
                ? "border-primary bg-primary/10 text-primary"
                : "bg-surface text-muted-foreground hover:text-foreground",
            )}
          >
            <Star className={cn("size-4", onlyFavorites && "fill-primary")} />
          </button>
        </div>
        <ScrollContainer
          isVisible={false}
          className="grid min-h-0 flex-1 auto-rows-max grid-cols-2 content-start gap-1.5"
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
                  ? `No ${onlyFavorites ? "favorites" : "GIFs"} for “${search}”.`
                  : "No favorites yet. Tap the star on a GIF to add it."}
              </p>
            )
          )}
          {results.map((gif) => {
            const favorite = favorites.isFavorite(gif.id);
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
                  aria-label={
                    favorite ? `Remove ${gif.alt} from favorites` : `Add ${gif.alt} to favorites`
                  }
                  aria-pressed={favorite}
                  onClick={() => favorites.toggle(gif.id)}
                  className="absolute right-1 top-1 flex size-8 items-center justify-center rounded-full bg-black/55 text-white transition-colors hover:bg-black/75"
                >
                  <Star className={cn("size-4", favorite && "fill-primary text-primary")} />
                </button>
              </div>
            );
          })}
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}
