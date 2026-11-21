import { useQuery } from "@tanstack/react-query";
import { listGifs, type Gif, type GifCatalog } from "./api";

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
 * arguing with the ref); an empty search shows the category.
 */
export function searchGifs(catalog: GifCatalog, query: string, category: string): Gif[] {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return catalog.gifs.filter((g) => g.category === category);
  const labels = new Map(catalog.categories.map((c) => [c.id, c.label.toLowerCase()]));
  return catalog.gifs.filter((g) => {
    const text = [g.alt, ...g.tags, labels.get(g.category) ?? ""].join(" ").toLowerCase();
    return words.every((word) => text.includes(word));
  });
}
