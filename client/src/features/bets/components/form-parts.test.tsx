import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Stepper } from "./form-parts";

describe("Stepper", () => {
  it("jumps by ±10 and ±100 inside its limits", () => {
    const onChange = vi.fn();
    render(
      <Stepper
        value={220.5}
        step={0.5}
        min={0.5}
        max={999.5}
        jumps={[10, 100]}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Add 100" }));
    expect(onChange).toHaveBeenLastCalledWith(320.5);
    fireEvent.click(screen.getByRole("button", { name: "Subtract 10" }));
    expect(onChange).toHaveBeenLastCalledWith(210.5);
    expect(screen.getAllByRole("button").map((b) => b.textContent)).toEqual([
      "",
      "",
      "−100",
      "−10",
      "+10",
      "+100",
    ]);
  });

  it("disables a jump that the limit would swallow", () => {
    render(<Stepper value={999.5} step={0.5} max={999.5} jumps={[10]} onChange={() => {}} />);
    expect(screen.getByRole("button", { name: "Add 10" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Subtract 10" })).toBeEnabled();
  });
});
