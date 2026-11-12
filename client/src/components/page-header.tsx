import type { ReactNode } from "react";

/** Page title block: small eyebrow label, big title, optional action on the right. */
export function PageHeader({
  eyebrow,
  title,
  action,
}: {
  eyebrow?: string;
  title: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex min-w-0 items-end justify-between gap-4 [&>*:last-child]:shrink [&>*:last-child]:min-w-0">
      <div>
        {eyebrow && (
          <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
            {eyebrow}
          </p>
        )}
        <h1 className="mt-1 text-4xl font-black uppercase leading-[0.9] md:text-5xl">{title}</h1>
      </div>
      {action}
    </div>
  );
}
