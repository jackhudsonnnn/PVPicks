import { describe, expect, it } from "vitest";
import { searchGifs, type GifCatalog } from "./gifs";

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

const ids = (query: string, category = "pain") =>
  searchGifs(catalog, query, category).map((g) => g.id);

describe("searchGifs", () => {
  it("shows the category when the search is empty", () => {
    expect(ids("  ")).toEqual(["a", "c"]);
  });

  it("needs every word to match the caption, tags or category", () => {
    expect(ids("lebron")).toEqual(["a", "b"]);
    expect(ids("LeBron ref")).toEqual(["a"]);
    expect(ids("lebron roast")).toEqual(["b"]);
    expect(ids("homelander goat")).toEqual([]);
  });
});
