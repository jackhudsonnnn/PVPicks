import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Ban, Flag, MoreHorizontal, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ConfirmDialog } from "@/components/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { cn } from "@/lib/utils";
import { myBlocks, useAppStore, useMe, useGetUser } from "@/features/store";
import { useAuth } from "@/features/auth/auth-provider";
import { NOTE_MAX, REPORT_REASONS, reportProblem, type ReportReason } from "../safety";
import { useReports } from "../reports-storage";
import { TomatoThrowButton } from "./tomato-throw-button";

/** What a report can point at besides the person. */
export interface ReportContext {
  tableId?: string | undefined;
  messageId?: string | undefined;
  messageText?: string | undefined;
}

function ReportDialog({
  userId,
  open,
  onOpenChange,
  context,
}: {
  userId: string;
  open: boolean;
  onOpenChange: (o: boolean) => void;
  context?: ReportContext | undefined;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { account } = useAuth();
  const { file } = useReports();
  const [reason, setReason] = useState<ReportReason>("harassment");
  const [note, setNote] = useState("");
  const [sent, setSent] = useState(false);
  const [blockOpen, setBlockOpen] = useState(false);
  const { state } = useAppStore();
  const name = getUser(userId).username.split(" ")[0];
  const problem = reportProblem({ reporterId: me, targetId: userId, reason, note });
  const alreadyBlocked = myBlocks(state).includes(userId);

  function close(o: boolean) {
    onOpenChange(o);
    if (!o) {
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
                  A person on the P2Picks team will review it. {name} won't know who reported them.
                  You can follow its status in Preferences, Safety.
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
                    ? "About this message. We'll include a copy so it can be reviewed."
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
                    or your table's host. Report only if it turns into harassment or pressure.{" "}
                    <Link
                      to="/how-to-play"
                      hash="settling-up"
                      onClick={() => close(false)}
                      className="font-semibold text-foreground underline"
                    >
                      Read the Guide on settling up
                    </Link>
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
                    note.length > NOTE_MAX ? "text-loss" : "text-muted-foreground",
                  )}
                >
                  {note.length}/{NOTE_MAX}
                </p>
              </div>
              {problem && note.length > 0 && <p className="text-xs text-loss">{problem}</p>}
              <DialogFooter className="gap-2">
                <Button variant="ghost" onClick={() => close(false)}>
                  Cancel
                </Button>
                <Button
                  variant="destructive"
                  disabled={!!problem}
                  onClick={() => {
                    file({
                      reporterId: me,
                      reporterAccount: account ?? undefined,
                      targetId: userId,
                      reason,
                      note: note.trim(),
                      ...context,
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
      <BlockDialog userId={userId} open={blockOpen} onOpenChange={setBlockOpen} />
    </>
  );
}

/** Confirm block or unblock. */
export function BlockDialog({
  userId,
  open,
  onOpenChange,
}: {
  userId: string;
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const blocked = myBlocks(state).includes(userId);
  const name = getUser(userId).username.split(" ")[0];
  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title={blocked ? `Unblock ${name}?` : `Block ${name}?`}
      description={
        blocked
          ? `You'll see ${name}'s chats again. You'll both need to follow each other again to be friends.`
          : `${name} won't be told. Their chats are hidden for you, you stop following each other, and neither of you can add the other to a table. Shared tables and past tickets stay as they are.`
      }
      destructive={!blocked}
      onConfirm={() => actions.block(userId, !blocked)}
      confirmLabel={blocked ? "Unblock" : "Block"}
    />
  );
}

/** "⋯" menu with Report and Block for a person (or one of their messages). */
export function SafetyMenu({
  userId,
  context,
  className,
}: {
  userId: string;
  context?: ReportContext | undefined;
  className?: string;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const [report, setReport] = useState(false);
  const [block, setBlock] = useState(false);
  if (userId === me || getUser(userId)?.deleted) return null;
  const blocked = myBlocks(state).includes(userId);
  const name = getUser(userId).username.split(" ")[0];
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label={`More options for ${name}`}
            className={cn(
              "flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground/60 transition-colors hover:bg-surface-raised hover:text-foreground data-[state=open]:bg-surface-raised data-[state=open]:text-foreground",
              className,
            )}
          >
            <MoreHorizontal className="size-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => setReport(true)}>
            <Flag /> {context?.messageId ? "Report message" : `Report ${name}`}
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => setBlock(true)} className="text-loss focus:text-loss">
            <Ban /> {blocked ? `Unblock ${name}` : `Block ${name}`}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <ReportDialog userId={userId} open={report} onOpenChange={setReport} context={context} />
      <BlockDialog userId={userId} open={block} onOpenChange={setBlock} />
    </>
  );
}

/** Report / Block buttons for a profile popup. */
export function SafetyButtons({
  userId,
  tableId,
  onThrow,
  throwDisabled,
}: {
  userId: string;
  tableId?: string | undefined;
  onThrow: () => void;
  /** Tomatoes land on a table seat, so they need a specific table. */
  throwDisabled?: boolean;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const [report, setReport] = useState(false);
  const [block, setBlock] = useState(false);
  if (userId === me || getUser(userId)?.deleted) return null;
  const blocked = myBlocks(state).includes(userId);
  return (
    <div className="flex flex-wrap gap-2">
      <TomatoThrowButton
        name={getUser(userId).username.split(" ")[0] ?? "this player"}
        onThrow={onThrow}
        disabled={throwDisabled ?? false}
      />
      <Button size="sm" variant="outline" onClick={() => setReport(true)}>
        <Flag /> Report
      </Button>
      <Button
        size="sm"
        variant="outline"
        onClick={() => setBlock(true)}
        className={cn(!blocked && "text-loss hover:text-loss")}
      >
        <Ban /> {blocked ? "Unblock" : "Block"}
      </Button>
      <ReportDialog userId={userId} open={report} onOpenChange={setReport} context={{ tableId }} />
      <BlockDialog userId={userId} open={block} onOpenChange={setBlock} />
    </div>
  );
}
