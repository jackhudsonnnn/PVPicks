import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { SettingsSection } from "@/components/settings";
import { backendErrorMessage } from "@/lib/api-client";
import { deleteAccount } from "./api";
import { deletionProblem } from "./delete-rules";

/**
 * Deleting your account, on the Account tab. Confirmed with your password; the server refuses until you're square
 * (no open bets, unsettled table points or open receipts) and says which. On success the session is gone.
 */
export function DeleteAccountSection({ onDeleted }: { onDeleted: () => void }) {
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      await deleteAccount(password);
      setOpen(false);
      onDeleted();
    } catch (error) {
      setProblem(deletionProblem(error) ?? backendErrorMessage(error));
    } finally {
      setBusy(false);
      setPassword("");
    }
  }

  return (
    <SettingsSection
      title="Delete account"
      description="Removes your profile, friends, chat messages and preferences. Bets and receipts stay, shown as “Deleted user”. Tables you host pass to their longest-standing member."
    >
      <div className="px-4 py-3.5 sm:px-5">
        <AlertDialog
          open={open}
          onOpenChange={(next) => {
            setOpen(next);
            setProblem(null);
            setPassword("");
          }}
        >
          <AlertDialogTrigger asChild>
            <Button variant="outline" className="border-loss/60 text-loss hover:bg-loss/10">
              Delete my account
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <form onSubmit={submit} className="space-y-4">
              <AlertDialogHeader>
                <AlertDialogTitle>Delete your account?</AlertDialogTitle>
                <AlertDialogDescription>
                  This can't be undone. You need to be square first: no open bets, unsettled table
                  points or open receipts.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <div className="space-y-1.5">
                <Label htmlFor="delete-password">Your password</Label>
                <Input
                  id="delete-password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  required
                />
              </div>
              {problem && (
                <p role="alert" className="text-sm text-loss">
                  {problem}
                </p>
              )}
              <AlertDialogFooter>
                <AlertDialogCancel type="button">Cancel</AlertDialogCancel>
                <Button type="submit" variant="destructive" disabled={busy || !password}>
                  {busy ? "Deleting…" : "Delete forever"}
                </Button>
              </AlertDialogFooter>
            </form>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </SettingsSection>
  );
}
