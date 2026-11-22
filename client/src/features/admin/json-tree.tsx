import { useState } from "react";
import { Check, ChevronRight, Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * A collapsible JSON tree. Each row shows its key, type and size; clicking a row selects it, and the bar on top
 * shows the selected path (`players[3].stats.pts`) with buttons to copy the path or the value. Objects open two
 * levels deep by default; Expand all / Collapse all reset that.
 */
export function JsonTree({ value, label = "root" }: { value: unknown; label?: string }) {
  const [selected, setSelected] = useState<{ path: string; value: unknown }>({
    path: label,
    value,
  });
  const [depth, setDepth] = useState<{ open: number; generation: number }>({
    open: 2,
    generation: 0,
  });
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/40 px-2 py-1.5">
        <code className="min-w-0 flex-1 truncate font-mono text-xs" title={selected.path}>
          {selected.path}
        </code>
        <CopyButton label="Copy path" text={selected.path} />
        <CopyButton label="Copy value" text={JSON.stringify(selected.value, null, 2)} />
        <Button
          size="sm"
          variant="ghost"
          onClick={() => setDepth((d) => ({ open: 99, generation: d.generation + 1 }))}
        >
          Expand all
        </Button>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => setDepth((d) => ({ open: 1, generation: d.generation + 1 }))}
        >
          Collapse all
        </Button>
      </div>
      <div
        className="max-h-[70vh] overflow-auto rounded-md border bg-surface p-2 font-mono text-xs"
        role="tree"
      >
        <Node
          key={depth.generation}
          name={label}
          path={label}
          value={value}
          level={0}
          openDepth={depth.open}
          selected={selected.path}
          onSelect={(path, node) => setSelected({ path, value: node })}
        />
      </div>
    </div>
  );
}

function CopyButton({ label, text }: { label: string; text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      size="sm"
      variant="ghost"
      onClick={() =>
        void navigator.clipboard?.writeText(text).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1200);
        })
      }
    >
      {copied ? <Check /> : <Copy />} {label}
    </Button>
  );
}

function childPath(path: string, key: string | number) {
  if (typeof key === "number") return `${path}[${key}]`;
  return /^[A-Za-z_$][\w$]*$/.test(key) ? `${path}.${key}` : `${path}[${JSON.stringify(key)}]`;
}

function summary(value: unknown) {
  if (Array.isArray(value)) return `[${value.length}]`;
  if (value && typeof value === "object") return `{${Object.keys(value).length}}`;
  return "";
}

/** Values stay in text colors (strings quoted, numbers bold, booleans italic), never series colors. */
function Scalar({ value }: { value: unknown }) {
  if (value === null) return <span className="text-muted-foreground">null</span>;
  if (typeof value === "string") return <span className="break-all">{JSON.stringify(value)}</span>;
  if (typeof value === "number") return <span className="font-bold">{value}</span>;
  if (typeof value === "boolean") return <span className="italic">{String(value)}</span>;
  return <span>{String(value)}</span>;
}

function Node({
  name,
  path,
  value,
  level,
  openDepth,
  selected,
  onSelect,
}: {
  name: string | number;
  path: string;
  value: unknown;
  level: number;
  openDepth: number;
  selected: string;
  onSelect: (path: string, value: unknown) => void;
}) {
  const branch = value !== null && typeof value === "object";
  const [open, setOpen] = useState(level < openDepth);
  const entries: [string | number, unknown][] = Array.isArray(value)
    ? value.map((item, i) => [i, item])
    : branch
      ? Object.entries(value as Record<string, unknown>)
      : [];
  return (
    <div
      role="treeitem"
      aria-expanded={branch ? open : undefined}
      aria-selected={selected === path}
    >
      <div
        className={cn(
          "flex cursor-pointer items-start gap-1 rounded px-1 py-0.5 hover:bg-muted/60",
          selected === path && "bg-primary/15",
        )}
        style={{ paddingLeft: level * 14 + 4 }}
        onClick={() => {
          onSelect(path, value);
          if (branch) setOpen((o) => !o);
        }}
      >
        {branch ? (
          <ChevronRight
            className={cn("mt-0.5 size-3 shrink-0 transition-transform", open && "rotate-90")}
          />
        ) : (
          <span className="w-3 shrink-0" />
        )}
        <span
          className={cn(
            "shrink-0",
            typeof name === "number" ? "text-muted-foreground" : "font-semibold",
          )}
        >
          {name}
        </span>
        <span className="text-muted-foreground">:</span>
        {branch ? (
          <span className="text-muted-foreground">{summary(value)}</span>
        ) : (
          <Scalar value={value} />
        )}
      </div>
      {branch &&
        open &&
        entries.map(([key, child]) => (
          <Node
            key={String(key)}
            name={key}
            path={childPath(path, key)}
            value={child}
            level={level + 1}
            openDepth={openDepth}
            selected={selected}
            onSelect={onSelect}
          />
        ))}
    </div>
  );
}
