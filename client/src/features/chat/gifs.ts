import { useQuery } from "@tanstack/react-query";
import { listGifs, type Gif, type GifCatalog } from "./api";

/**
 * The chat GIF library (hosted on Tenor's CDN). The server owns it (`GET /gifs`) and accepts only its ids, so a
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

/** Matches a search against alt text and tags; an empty search shows the category. */
export type { Gif, GifCatalog };

export function searchGifs(catalog: GifCatalog, query: string, category: string): Gif[] {
  const q = query.trim().toLowerCase();
  if (!q) return catalog.gifs.filter((g) => g.category === category);
  return catalog.gifs.filter(
    (g) => g.alt.toLowerCase().includes(q) || g.tags.some((t) => t.includes(q)),
  );
}
