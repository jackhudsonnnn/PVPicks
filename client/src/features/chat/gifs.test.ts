import { describe, expect, it } from "vitest";
import { favoriteGifs, searchGifs, type GifCatalog } from "./gifs";

const catalog: GifCatalog = {
  categories: [
    { id: "pain", label: "Pain" },
    { id: "roast", label: "Roast" },
  ],
  gifs: [
    {
      id: "a",
      url: "a.webp",
      alt: "LeBron arguing with the ref",
      category: "pain",
      tags: ["lebron", "ref"],
    },
    { id: "b", url: "b.webp", alt: "LeBron the GOAT", category: "roast", tags: ["lebron", "goat"] },
    { id: "c", url: "c.webp", alt: "Sad Homelander", category: "pain", tags: ["the boys", "sad"] },
  ],
};

const ids = (query: string, gifs = catalog.gifs) =>
  searchGifs(catalog, query, gifs).map((g) => g.id);

describe("searchGifs", () => {
  it("shows everything when the search is empty", () => {
    expect(ids("  ")).toEqual(["a", "b", "c"]);
  });

  it("searches just your favorites, newest first", () => {
    const favorites = favoriteGifs(catalog, ["c", "a", "gone"]);
    expect(ids("", favorites)).toEqual(["c", "a"]);
    expect(ids("lebron", favorites)).toEqual(["a"]);
  });

  it("needs every word to match the caption, tags or category", () => {
    expect(ids("lebron")).toEqual(["a", "b"]);
    expect(ids("LeBron ref")).toEqual(["a"]);
    expect(ids("lebron roast")).toEqual(["b"]);
    expect(ids("homelander goat")).toEqual([]);
  });
});
