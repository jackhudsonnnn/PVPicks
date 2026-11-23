import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RangeSlider, Stepper, ValueSlider } from "./form-parts";

describe("ValueSlider", () => {
  it("shows the value large, with Min, −step, +step and Max on one line under the slider", () => {
    const onChange = vi.fn();
    render(
      <ValueSlider
        value={220.5}
        step={1}
        min={0.5}
        max={999.5}
        sliderMax={399.5}
        label="Total points line"
        onChange={onChange}
      />,
    );
    expect(screen.getByLabelText("Total points line")).toHaveTextContent("220.5");
    expect(screen.getAllByRole("button").map((b) => b.textContent)).toEqual([
      "Min 0.5",
      "−1",
      "+1",
      "Max 399.5",
    ]);
    fireEvent.click(screen.getByRole("button", { name: "Increase Total points line" }));
    expect(onChange).toHaveBeenLastCalledWith(221.5);
    fireEvent.click(screen.getByRole("button", { name: "Total points line minimum, 0.5" }));
    expect(onChange).toHaveBeenLastCalledWith(0.5);
    fireEvent.click(screen.getByRole("button", { name: "Total points line maximum, 399.5" }));
    expect(onChange).toHaveBeenLastCalledWith(399.5);
    const slider = screen.getByRole("slider", { name: "Total points line slider" });
    expect(slider).toHaveAttribute("aria-valuemax", "399.5");
  });

  it("stops + and Max at the shown Max", () => {
    render(
      <ValueSlider
        value={399.5}
        step={1}
        min={0.5}
        max={999.5}
        sliderMax={399.5}
        onChange={() => {}}
      />,
    );
    expect(screen.getByRole("button", { name: "Increase" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Maximum/ })).toBeDisabled();
  });

  it("keeps a value already above the cap, and stops at the ends", () => {
    render(
      <ValueSlider value={150} step={1} min={1} max={150} sliderMax={100} onChange={() => {}} />,
    );
    expect(screen.getByRole("slider")).toHaveAttribute("aria-valuemax", "150");
    expect(screen.getByRole("button", { name: "Increase" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Maximum/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Minimum/ })).toBeEnabled();
  });

  it("formats the steps like the value and shows a note after it", () => {
    render(
      <ValueSlider
        value={0}
        step={10}
        min={0}
        max={1000}
        format={(v) => (v / 100).toFixed(2)}
        note="(4fun)"
        onChange={() => {}}
      />,
    );
    expect(screen.getByText("(4fun)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Increase" })).toHaveTextContent("+0.10");
    expect(screen.getByRole("button", { name: "Maximum, 10.00" })).toBeEnabled();
  });
});

describe("Stepper", () => {
  it("formats the step like the value and stops at its limits", () => {
    render(
      <Stepper value={60} step={5} min={10} max={60} format={(v) => `${v}s`} onChange={() => {}} />,
    );
    expect(screen.getByRole("button", { name: "Increase" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Decrease" })).toHaveTextContent("5s");
  });
});

describe("RangeSlider", () => {
  it("shows the range and moves either end with its own thumb", () => {
    const onChange = vi.fn();
    render(
      <RangeSlider
        low={2}
        high={7}
        min={2}
        max={7}
        label="Players"
        unit="players"
        onChange={onChange}
      />,
    );
    expect(screen.getByText("2–7")).toBeInTheDocument();
    const fewest = screen.getByRole("slider", { name: "Fewest players" });
    fewest.focus();
    fireEvent.keyDown(fewest, { key: "ArrowRight" });
    expect(onChange).toHaveBeenLastCalledWith(3, 7);
    const most = screen.getByRole("slider", { name: "Most players" });
    most.focus();
    fireEvent.keyDown(most, { key: "ArrowLeft" });
    expect(onChange).toHaveBeenLastCalledWith(2, 6);
  });
});
