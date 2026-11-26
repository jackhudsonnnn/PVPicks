import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { listGifFavorites, listGifs, setGifFavorite, type Gif, type GifCatalog } from "./api";

/**
 * The chat GIF library (hosted on Tenor and GIPHY CDNs). The server owns it (`GET /gifs`) and accepts only its ids, so a
 * live GIF search can replace it there without changing the picker.
 */
/** The library, loaded once per session (it changes only with a deploy). */
export function useGifs() {
  return useQuery({
    queryKey: ["gifs"],
    queryFn: ({ signal }) => listGifs(signal),
    staleTime: Infinity,
    retry: false,
  });
}

export type { Gif, GifCatalog };

/**
 * Every word of the search has to appear in the GIF's caption, tags or category ("lebron ref" finds LeBron
 * arguing with the ref); an empty search keeps them all. Searches the whole library, or just the given GIFs.
 */
export function searchGifs(catalog: GifCatalog, query: string, gifs: Gif[] = catalog.gifs): Gif[] {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return gifs;
  const labels = new Map(catalog.categories.map((c) => [c.id, c.label.toLowerCase()]));
  return gifs.filter((g) => {
    const text = [g.alt, ...g.tags, labels.get(g.category) ?? ""].join(" ").toLowerCase();
    return words.every((word) => text.includes(word));
  });
}

/**
 * Your favorite GIFs, newest first, kept with your account. Adding or removing one updates the list at once and rolls
 * back if the server refuses (for example at the 100-favorite cap).
 */
export function useGifFavorites(userId: string) {
  const queryClient = useQueryClient();
  const key = userKey(userId, "gif-favorites");
  const favorites = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => listGifFavorites(signal),
    staleTime: Infinity,
    retry: false,
  });
  const toggle = useMutation({
    mutationFn: ({ gifId, favorite }: { gifId: string; favorite: boolean }) =>
      setGifFavorite(gifId, favorite, crypto.randomUUID()),
    onMutate: ({ gifId, favorite }) => {
      const before = queryClient.getQueryData<string[]>(key) ?? [];
      queryClient.setQueryData<string[]>(
        key,
        favorite
          ? [gifId, ...before.filter((id) => id !== gifId)]
          : before.filter((id) => id !== gifId),
      );
      return { before };
    },
    onError: (error, _vars, context) => {
      if (context) queryClient.setQueryData(key, context.before);
      toast.error(backendErrorMessage(error));
    },
  });
  const ids = favorites.data ?? [];
  return {
    ids,
    isFavorite: (gifId: string) => ids.includes(gifId),
    toggle: (gifId: string) => toggle.mutate({ gifId, favorite: !ids.includes(gifId) }),
  };
}

/** Your favorite GIFs from the library, in the order you added them (newest first). */
export function favoriteGifs(catalog: GifCatalog, ids: string[]): Gif[] {
  const byId = new Map(catalog.gifs.map((g) => [g.id, g]));
  return ids.flatMap((id) => {
    const gif = byId.get(id);
    return gif ? [gif] : [];
  });
}
