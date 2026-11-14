import { useState } from "react";
import { Ban, Flag, MoreHorizontal, ShieldCheck } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Textarea } from "@/components/ui/textarea";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { cn } from "@/lib/utils";
import type { ReportReason } from "./social-api";
import { NOTE_MAX, REPORT_REASONS, reportProblem } from "./report-rules";
import type { useSocialActions } from "./social-actions";
import { useRelationships } from "./social-queries";

export type SocialActions = ReturnType<typeof useSocialActions>;

export interface ReportContext {
  tableId?: string | undefined;
  messageId?: string | undefined;
  /** Shown in the dialog; the server keeps its own copy of the message. */
  messageText?: string | undefined;
}

interface SafetyProps {
  /** The person being reported or blocked. */
  userId: string;
  name: string;
  /** The viewer. */
  viewerId: string;
  actions: SocialActions;
}

export function ReportDialog({
  userId,
  name,
  viewerId,
  actions,
  context,
  open,
  onOpenChange,
}: SafetyProps & {
  context?: ReportContext | undefined;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [reason, setReason] = useState<ReportReason>("harassment");
  const [note, setNote] = useState("");
  const [sent, setSent] = useState(false);
  const [blockOpen, setBlockOpen] = useState(false);
  const relationships = useRelationships(viewerId);
  const alreadyBlocked = relationships.data?.blockedIds.includes(userId) ?? false;
  const problem = reportProblem(reason, note);
  const busy = actions.busy || !!actions.pending || !!actions.storageError;

  function close(next: boolean) {
    onOpenChange(next);
    if (!next) {
      setSent(false);
      setNote("");
      setReason("harassment");
    }
  }

  return (
    <>
      <Dialog open={open} onOpenChange={close}>
        <DialogContent className="max-w-md">
          {sent ? (
            <>
              <DialogHeader>
                <span className="flex size-12 items-center justify-center rounded-2xl bg-primary/15 text-primary">
                  <ShieldCheck className="size-6" />
                </span>
                <DialogTitle className="font-display text-3xl font-black uppercase">
                  Report sent
                </DialogTitle>
                <DialogDescription>
                  The P2Picks team will review it. {name} won't know who reported them.
                </DialogDescription>
              </DialogHeader>
              <DialogFooter className="gap-2">
                {!alreadyBlocked && (
                  <Button
                    variant="outline"
                    onClick={() => {
                      close(false);
                      setBlockOpen(true);
                    }}
                  >
                    <Ban /> Block {name} too
                  </Button>
                )}
                <Button onClick={() => close(false)}>Done</Button>
              </DialogFooter>
            </>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle className="font-display text-3xl font-black uppercase">
                  Report {name}
                </DialogTitle>
                <DialogDescription>
                  {context?.messageText
                    ? "About this message. A copy goes with your report."
                    : "Tell us what's going on. Reports are private."}
                </DialogDescription>
              </DialogHeader>
              {context?.messageText && (
                <p className="break-words rounded-lg border bg-surface px-3 py-2 text-sm italic text-muted-foreground">
                  “{context.messageText}”
                </p>
              )}
              <div className="space-y-2">
                <SegmentedTabs<ReportReason>
                  size="sm"
                  ariaLabel="Reason"
                  tabs={REPORT_REASONS.map((r) => ({ id: r.id, label: r.label }))}
                  value={reason}
                  onChange={setReason}
                />
                <p className="text-xs text-muted-foreground">
                  {REPORT_REASONS.find((r) => r.id === reason)?.description}
                </p>
              </div>
              {reason === "settlement" && (
                <div className="space-y-1.5 rounded-lg border border-invite/50 bg-invite/10 px-3 py-2.5 text-xs leading-relaxed">
                  <p className="font-semibold">Settlement disputes are handled privately.</p>
                  <p className="text-muted-foreground">
                    P2Picks doesn't hold money and can't make anyone pay, so sort it out with {name}{" "}
                    or your table's host. Report only if it turns into harassment or pressure.
                  </p>
                </div>
              )}
              <div className="space-y-1">
                <Textarea
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  maxLength={NOTE_MAX + 50}
                  placeholder={reason === "other" ? "What happened?" : "Anything else? (optional)"}
                  aria-label="Details"
                  className="min-h-24 bg-surface"
                />
                <p
                  className={cn(
                    "text-right font-mono text-[10px]",
                    [...note].length > NOTE_MAX ? "text-loss" : "text-muted-foreground",
                  )}
                >
                  {[...note].length}/{NOTE_MAX}
                </p>
              </div>
              {problem && note.length > 0 && <p className="text-xs text-loss">{problem}</p>}
              <DialogFooter className="gap-2">
                <Button variant="ghost" onClick={() => close(false)}>
                  Cancel
                </Button>
                <Button
                  variant="destructive"
                  disabled={!!problem || busy}
                  onClick={() => {
                    actions.start({
                      kind: "report",
                      targetId: userId,
                      reason,
                      note: note.trim(),
                      ...(context?.tableId ? { tableId: context.tableId } : {}),
                      ...(context?.messageId ? { messageId: context.messageId } : {}),
                    });
                    setSent(true);
                  }}
                >
                  <Flag /> Send report
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>
      <BlockDialog
        userId={userId}
        name={name}
        viewerId={viewerId}
        actions={actions}
        open={blockOpen}
        onOpenChange={setBlockOpen}
      />
    </>
  );
}

/** Confirm block or unblock. Blocking is private: they aren't told. */
export function BlockDialog({
  userId,
  name,
  viewerId,
  actions,
  open,
  onOpenChange,
}: SafetyProps & { open: boolean; onOpenChange: (open: boolean) => void }) {
  const relationships = useRelationships(viewerId);
  const blocked = relationships.data?.blockedIds.includes(userId) ?? false;
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{blocked ? `Unblock ${name}?` : `Block ${name}?`}</AlertDialogTitle>
          <AlertDialogDescription>
            {blocked
              ? "You'll see their messages again. Follows between you aren't restored."
              : "They won't be told. You won't see their messages, follows between you end, and neither of you can add the other to a table. Bets and points at shared tables are unaffected."}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction
            onClick={() => actions.start({ kind: "block", userId, block: !blocked })}
          >
            {blocked ? "Unblock" : "Block"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** "⋯" menu with Report and Block for a person, or one of their messages. */
export function SafetyMenu({
  context,
  className,
  ...props
}: SafetyProps & { context?: ReportContext | undefined; className?: string }) {
  const [report, setReport] = useState(false);
  const [block, setBlock] = useState(false);
  const relationships = useRelationships(props.viewerId);
  const blocked = relationships.data?.blockedIds.includes(props.userId) ?? false;
  return (
    <>
      {/* Non-modal: the dialog it opens must own focus, or the two focus traps fight. */}
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label={`More options for ${props.name}`}
            className={cn(
              "flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground/60 transition-colors hover:bg-surface-raised hover:text-foreground",
              className,
            )}
          >
            <MoreHorizontal className="size-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => setReport(true)}>
            <Flag /> Report
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => setBlock(true)}>
            <Ban /> {blocked ? "Unblock" : "Block"}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <ReportDialog {...props} context={context} open={report} onOpenChange={setReport} />
      <BlockDialog {...props} open={block} onOpenChange={setBlock} />
    </>
  );
}
