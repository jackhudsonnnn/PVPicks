import { describe, expect, it } from "vitest";
import { MODES } from "./modes";
import { MODE_INFO } from "./mode-info";

describe("mode info", () => {
  it("explains every mode with 2 or 3 worked examples", () => {
    for (const mode of MODES) {
      const info = MODE_INFO[mode.id];
      expect(info.how.length, mode.id).toBeGreaterThan(0);
      expect(info.notes.length, mode.id).toBeGreaterThan(0);
      expect(info.examples.length, mode.id).toBeGreaterThanOrEqual(2);
      expect(info.examples.length, mode.id).toBeLessThanOrEqual(3);
      for (const example of info.examples) {
        expect(example.setup.length, `${mode.id}: ${example.title}`).toBeGreaterThan(0);
      }
    }
  });
});
