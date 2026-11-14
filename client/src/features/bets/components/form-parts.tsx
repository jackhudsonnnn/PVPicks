import type { ReactNode } from "react";

/** Labeled block inside the proposal sheet. */
export function Section({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section className="space-y-2.5">
      <h4 className="font-mono text-[11px] font-bold uppercase tracking-widest text-muted-foreground">
        {label}
      </h4>
      {children}
    </section>
  );
}
