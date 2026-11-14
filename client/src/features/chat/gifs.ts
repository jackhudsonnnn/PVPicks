/**
 * Curated GIF library for table chat (hosted on Tenor's CDN).
 * Swap for a live GIF search API later without changing the picker UI.
 */
export interface Gif {
  id: string;
  url: string;
  alt: string;
  category: GifCategory;
  tags: string[];
}

export type GifCategory = "hype" | "money" | "pain" | "react";

export const GIF_CATEGORIES: { id: GifCategory; label: string }[] = [
  { id: "hype", label: "Hype" },
  { id: "money", label: "Money" },
  { id: "pain", label: "Pain" },
  { id: "react", label: "React" },
];

const T = (path: string) => `https://media.tenor.com/${path}.gif`;

export const GIFS: Gif[] = [
  {
    id: "g1",
    url: T("5lPd211l4iUAAAAM/cowboys-touchdown"),
    alt: "Touchdown celebration",
    category: "hype",
    tags: ["touchdown", "score", "td"],
  },
  {
    id: "g2",
    url: T("q89NzmL_UoAAAAAM/spike-touchdown"),
    alt: "Touchdown spike",
    category: "hype",
    tags: ["touchdown", "spike"],
  },
  {
    id: "g3",
    url: T("-d9dW8v-M7IAAAAM/ufl-xfl"),
    alt: "Football celebration",
    category: "hype",
    tags: ["touchdown", "celebrate"],
  },
  {
    id: "g4",
    url: T("TPXMriXwLD4AAAAM/lets-go-the-rock"),
    alt: "The Rock: let's go",
    category: "hype",
    tags: ["lets go", "hype", "rock"],
  },
  {
    id: "g5",
    url: T("ovaTnYdCFFUAAAAM/wolf-of-wall-street-lets-goo"),
    alt: "Wolf of Wall Street: let's go",
    category: "hype",
    tags: ["lets go", "hype"],
  },
  {
    id: "g6",
    url: T("OOMl9O8GdYUAAAAM/spongebob-patrick-star"),
    alt: "SpongeBob and Patrick celebrating",
    category: "hype",
    tags: ["celebrate", "party", "spongebob"],
  },
  {
    id: "g7",
    url: T("e2Abg24uiv4AAAAM/make-it-rain-money-money-make-it-rain"),
    alt: "Make it rain",
    category: "money",
    tags: ["money", "rain", "rich"],
  },
  {
    id: "g8",
    url: T("TSLCnBKrt8oAAAAM/money-show-me-the-money"),
    alt: "Show me the money",
    category: "money",
    tags: ["money", "show me"],
  },
  {
    id: "g9",
    url: T("hXarrACgFwMAAAAM/pay-me-the-simpsons"),
    alt: "Simpsons: pay me",
    category: "money",
    tags: ["pay me", "pay up", "simpsons"],
  },
  {
    id: "g10",
    url: T("mPSu1gWReVAAAAAM/pay-me-my-money-payday"),
    alt: "Pay me my money",
    category: "money",
    tags: ["pay me", "pay up"],
  },
  {
    id: "g11",
    url: T("WLrC9VcfwpgAAAAM/cat-orange-cat"),
    alt: "Cat with money",
    category: "money",
    tags: ["money", "cat"],
  },
  {
    id: "g12",
    url: T("ZFc20z8DItkAAAAM/facepalm-really"),
    alt: "Facepalm: really?",
    category: "pain",
    tags: ["facepalm", "really", "ugh"],
  },
  {
    id: "g13",
    url: T("gDH3gN7zM1AAAAAM/facepalm-homer-simpson"),
    alt: "Homer facepalm",
    category: "pain",
    tags: ["facepalm", "homer", "doh"],
  },
  {
    id: "g14",
    url: T("dUFSMab-AH4AAAAM/dog-meme-meme-dog"),
    alt: "Crying dog",
    category: "pain",
    tags: ["crying", "sad", "dog"],
  },
  {
    id: "g15",
    url: T("TrGze9JX0bgAAAAM/fumble-lebron"),
    alt: "Fumble",
    category: "pain",
    tags: ["fumble", "drop"],
  },
  {
    id: "g16",
    url: T("8IBznsxApkwAAAAM/dak-fumble"),
    alt: "Dak fumble",
    category: "pain",
    tags: ["fumble", "cowboys", "dak"],
  },
  {
    id: "g17",
    url: T("zkXSl00kD_8AAAAM/catch-fumble"),
    alt: "Dropped catch",
    category: "pain",
    tags: ["fumble", "drop", "catch"],
  },
  {
    id: "g18",
    url: T("N0BcIlIQ-TQAAAAM/sweating-gif-nervous"),
    alt: "Nervous sweating",
    category: "react",
    tags: ["nervous", "sweating"],
  },
  {
    id: "g19",
    url: T("mFS--WKbbnYAAAAM/nervous-mouse"),
    alt: "Nervous mouse",
    category: "react",
    tags: ["nervous", "scared"],
  },
  {
    id: "g20",
    url: T("3eIvVsG3yPYAAAAM/the-universe-tim-and-eric-mind-blown"),
    alt: "Mind blown",
    category: "react",
    tags: ["mind blown", "wow", "shocked"],
  },
  {
    id: "g21",
    url: T("IklKDJCAZHgAAAAM/confused-cat"),
    alt: "Confused cat",
    category: "react",
    tags: ["confused", "what", "cat"],
  },
  {
    id: "g22",
    url: T("rL4hulhuEIYAAAAM/funny-laughing"),
    alt: "Laughing",
    category: "react",
    tags: ["laughing", "lol", "funny"],
  },
  {
    id: "g23",
    url: T("gL9l218k-xQAAAAM/flag"),
    alt: "Penalty flag",
    category: "react",
    tags: ["referee", "flag", "penalty", "ref"],
  },
  {
    id: "g24",
    url: T("breKZClwN9kAAAAM/referee-sassy"),
    alt: "Sassy referee",
    category: "react",
    tags: ["referee", "ref"],
  },
];

export const getGif = (id: string) => GIFS.find((g) => g.id === id);

/** Match a search against alt text and tags; empty search returns the category. */
export function searchGifs(query: string, category: GifCategory): Gif[] {
  const q = query.trim().toLowerCase();
  if (!q) return GIFS.filter((g) => g.category === category);
  return GIFS.filter((g) => g.alt.toLowerCase().includes(q) || g.tags.some((t) => t.includes(q)));
}
