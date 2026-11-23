import * as React from "react";
import * as SliderPrimitive from "@radix-ui/react-slider";

import { cn } from "@/lib/utils";

/**
 * A slider with a thumb per value (arrow keys step, Page Up/Down jump, Home/End go to the ends). `thumbLabel` names
 * the thumbs, one name each for a range.
 */
const Slider = React.forwardRef<
  React.ElementRef<typeof SliderPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SliderPrimitive.Root> & { thumbLabel?: string | string[] }
>(({ className, thumbLabel, ...props }, ref) => (
  <SliderPrimitive.Root
    ref={ref}
    className={cn(
      "relative flex h-6 w-full touch-none select-none items-center data-[disabled]:opacity-50",
      className,
    )}
    {...props}
  >
    <SliderPrimitive.Track className="relative h-1.5 w-full grow overflow-hidden rounded-full bg-input">
      <SliderPrimitive.Range className="absolute h-full bg-primary" />
    </SliderPrimitive.Track>
    {(props.value ?? props.defaultValue ?? [0]).map((_, i) => (
      <SliderPrimitive.Thumb
        key={i}
        aria-label={Array.isArray(thumbLabel) ? thumbLabel[i] : thumbLabel}
        className="block size-5 cursor-grab rounded-full border-2 border-primary bg-background shadow-md transition-transform focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-110 active:cursor-grabbing"
      />
    ))}
  </SliderPrimitive.Root>
));
Slider.displayName = SliderPrimitive.Root.displayName;

export { Slider };
