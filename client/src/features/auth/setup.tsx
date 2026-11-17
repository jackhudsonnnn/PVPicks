import { useState } from "react";
import { KeyRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Logo } from "@/components/logo";
import { Spinner } from "@/components/loading";
import { BackendError, backendErrorMessage } from "@/lib/api-client";
import { redeemSetupCode } from "./api";
import { passwordProblem } from "./password-rules";

type CurrentUser = Awaited<ReturnType<typeof redeemSetupCode>>;

/** Reads the code from `/setup#<code>`; the fragment never reaches the server or proxy logs. */
function codeFromLocation() {
  return typeof window === "undefined" ? "" : decodeURIComponent(window.location.hash.slice(1));
}

/**
 * Setting a password from an admin's setup or reset link. On success the player is signed in, and every other
 * session of the account ends.
 */
export function SetupPassword({ onDone }: { onDone: (user: CurrentUser) => Promise<void> }) {
  const [code] = useState(codeFromLocation);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  const problem = passwordProblem(password);
  const mismatch = confirm.length > 0 && confirm !== password;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setTouched(true);
    if (problem || password !== confirm) return;
    setBusy(true);
    setError(null);
    try {
      const user = await redeemSetupCode(code, password);
      window.history.replaceState(null, "", "/");
      await onDone(user);
    } catch (failure) {
      setError(
        failure instanceof BackendError && failure.code === "invalid_setup_code"
          ? "This link has expired or was already used. Ask the admin for a new one."
          : failure instanceof BackendError && failure.code === "weak_password" && failure.detail
            ? failure.detail
            : failure instanceof BackendError && failure.status === 429
              ? "Too many tries from this device. Wait a few minutes and try again."
              : backendErrorMessage(failure),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="grid min-h-dvh place-items-center bg-field px-4 py-10">
      <div className="w-full max-w-sm space-y-6 rounded-xl border bg-surface p-6 shadow-sm">
        <Logo asLink={false} />
        <div className="space-y-1">
          <h1 className="font-display text-3xl font-bold uppercase">Set your password</h1>
          <p className="text-sm text-muted-foreground">
            At least 12 characters. A few random words make a good one.
          </p>
        </div>
        {!code ? (
          <p role="alert" className="text-sm text-destructive">
            This link is missing its code. Ask the admin for a new one.
          </p>
        ) : (
          <form className="space-y-4" onSubmit={(event) => void submit(event)} noValidate>
            <div className="space-y-1.5">
              <Label htmlFor="setup-password">New password</Label>
              <Input
                id="setup-password"
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={touched && problem !== null}
                aria-describedby="setup-password-hint"
              />
              <p id="setup-password-hint" className="text-xs text-muted-foreground">
                {touched && problem ? problem : `${[...password].length} of at least 12 characters`}
              </p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="setup-confirm">Type it again</Label>
              <Input
                id="setup-confirm"
                type="password"
                autoComplete="new-password"
                value={confirm}
                onChange={(event) => setConfirm(event.target.value)}
                aria-invalid={mismatch}
              />
              {mismatch && <p className="text-xs text-destructive">The passwords don't match.</p>}
            </div>
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? <Spinner /> : <KeyRound />} Set password and sign in
            </Button>
          </form>
        )}
      </div>
    </main>
  );
}
