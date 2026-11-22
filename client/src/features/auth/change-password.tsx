import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { SettingsSection } from "@/components/settings";
import { BackendError, backendErrorMessage } from "@/lib/api-client";
import { changePassword } from "./api";
import { passwordProblem } from "./password-rules";

/** Changing your own password with the current one. Other devices are signed out; this one stays. */
export function ChangePasswordSection({ username }: { username: string }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const problem = next ? passwordProblem(next, username) : null;
  const mismatch = confirm.length > 0 && confirm !== next;
  const ready = current.length > 0 && next.length > 0 && !problem && confirm === next;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!ready) return;
    setBusy(true);
    setMessage(null);
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      setMessage({ ok: true, text: "Password changed. Your other devices are signed out." });
    } catch (failure) {
      setMessage({
        ok: false,
        text:
          failure instanceof BackendError && failure.code === "wrong_password"
            ? "Your current password isn't right."
            : failure instanceof BackendError && failure.code === "weak_password" && failure.detail
              ? failure.detail
              : failure instanceof BackendError && failure.status === 429
                ? "Too many tries. Wait a few minutes and try again."
                : backendErrorMessage(failure),
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <SettingsSection
      title="Password"
      description="At least 12 characters. Changing it signs out your other devices."
    >
      <form
        className="space-y-3 px-4 py-4 sm:px-5"
        onSubmit={(event) => void submit(event)}
        noValidate
      >
        <div className="space-y-1.5">
          <Label htmlFor="current-password">Current password</Label>
          <Input
            id="current-password"
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="new-password">New password</Label>
          <Input
            id="new-password"
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(event) => setNext(event.target.value)}
            aria-invalid={problem !== null}
          />
          {problem && <p className="text-xs text-destructive">{problem}</p>}
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="confirm-password">Type it again</Label>
          <Input
            id="confirm-password"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            aria-invalid={mismatch}
          />
          {mismatch && <p className="text-xs text-destructive">The passwords don't match.</p>}
        </div>
        {message && (
          <p
            role={message.ok ? "status" : "alert"}
            className={message.ok ? "text-sm text-muted-foreground" : "text-sm text-destructive"}
          >
            {message.text}
          </p>
        )}
        <Button type="submit" disabled={!ready || busy}>
          Change password
        </Button>
      </form>
    </SettingsSection>
  );
}
