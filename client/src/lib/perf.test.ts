import { describe, expect, it } from "vitest";
import { pageRoute, recordApiCall, takeSamples } from "./perf";

describe("browser perf samples", () => {
  it("reports page routes with ids replaced, and nothing it can't name", () => {
    expect(pageRoute("/")).toBe("/");
    expect(pageRoute("/tables/123e4567-e89b-42d3-a456-426614174000")).toBe("/tables/$id");
    expect(pageRoute("/Tickets")).toBe("/tickets");
    expect(pageRoute("/weird path!")).toBeNull();
  });

  it("buffers API calls without their query strings and skips its own reports", () => {
    takeSamples(1000);
    recordApiCall("get", "/tables?archived=true", 200, 12.6);
    recordApiCall("POST", "/perf", 204, 3);
    expect(takeSamples()).toEqual([
      { kind: "api", method: "GET", path: "/tables", status: 200, ms: 13 },
    ]);
  });
});
