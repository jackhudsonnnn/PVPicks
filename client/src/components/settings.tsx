import type { ComponentType, ReactNode } from "react";
import { cn } from "@/lib/utils";

/** A titled card that groups related settings rows. */
export function SettingsSection({
  title,
  description,
  action,
  children,
  className,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("rounded-2xl border bg-surface", className)}>
      <header className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-3 border-b px-4 py-3 sm:px-5">
        <div className="min-w-0">
          <h2 className="font-display text-xl font-black uppercase leading-none">{title}</h2>
          {description && <p className="mt-1 text-xs text-muted-foreground">{description}</p>}
        </div>
        {action}
      </header>
      <div className="divide-y">{children}</div>
    </section>
  );
}

/** One setting: icon, label, helper text and a control on the right (switch, tabs…). */
export function SettingRow({
  icon: Icon,
  label,
  description,
  control,
  disabled,
  children,
  htmlFor,
}: {
  icon?: ComponentType<{ className?: string }>;
  label: string;
  description?: string;
  control?: ReactNode;
  disabled?: boolean;
  /** Extra content shown under the row (e.g. a scope picker). */
  children?: ReactNode;
  htmlFor?: string;
}) {
  return (
    <div className={cn("px-4 py-3.5 transition-opacity sm:px-5", disabled && "opacity-50")}>
      <div className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-4">
        <label htmlFor={htmlFor} className="flex min-w-0 items-start gap-3">
          {Icon && (
            <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-primary">
              <Icon className="size-4" />
            </span>
          )}
          <span className="min-w-0">
            <span className="block text-sm font-semibold">{label}</span>
            {description && (
              <span className="mt-0.5 block text-xs text-muted-foreground">{description}</span>
            )}
          </span>
        </label>
        {control}
      </div>
      {children && <div className={cn("mt-3", Icon && "sm:pl-11")}>{children}</div>}
    </div>
  );
}
