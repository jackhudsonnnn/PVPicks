import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Logo } from "@/components/logo";
import { BackendError, backendErrorMessage, isUnauthorized } from "@/lib/api-client";
import { demoAccounts, type DemoAccount } from "./api";
import { DemoPicker } from "./demo-picker";

const frozenMessage = "This account is frozen. Contact an admin if you think that's a mistake.";

/**
 * Sign-in: the demo's account picker when the backend has the demo (dev), otherwise the password form. Accounts
 * are provisioned, so there is no sign-up.
 */
export function SignIn({
  login,
  loginAs,
  notice,
}: {
  login: (username: string, password: string) => Promise<void>;
  loginAs: (userId: string) => Promise<void>;
  notice: string | null;
}) {
  const demo = useQuery({
    queryKey: ["demo-accounts", ""],
    queryFn: ({ signal }) => demoAccounts("", signal),
    retry: false,
  });
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      setPassword("");
    } catch (failure) {
      setError(
        failure instanceof BackendError && failure.code === "account_frozen"
          ? frozenMessage
          : isUnauthorized(failure)
            ? "That username and password don't match an active account."
            : backendErrorMessage(failure),
      );
    } finally {
      setBusy(false);
    }
  }

  async function pick(account: DemoAccount) {
    setBusy(true);
    setError(null);
    try {
      await loginAs(account.id);
    } catch (failure) {
      setError(
        failure instanceof BackendError && failure.code === "account_frozen"
          ? frozenMessage
          : backendErrorMessage(failure),
      );
      void demo.refetch();
    } finally {
      setBusy(false);
    }
  }

  // A failed demo check falls back to the password form rather than blocking sign-in.
  if (demo.isPending)
    return <p className="p-6 text-sm text-muted-foreground">Checking sign-in options…</p>;

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-sm space-y-6">
        <Logo asLink={false} className="justify-center" />
        {notice && (
          <p role="status" className="text-sm">
            {notice}
          </p>
        )}
        {demo.data ? (
          <>
            <DemoPicker initial={demo.data} busy={busy} pick={(account) => void pick(account)} />
            {error && (
              <p role="alert" className="text-sm text-loss">
                {error}
              </p>
            )}
          </>
        ) : (
          <form onSubmit={submit} className="space-y-4 rounded-2xl border bg-surface p-5">
            <h1 className="font-display text-2xl font-extrabold uppercase">Sign in</h1>
            <p className="text-sm text-muted-foreground">
              Use a provisioned backend account. Registration is not available yet.
            </p>
            <div className="space-y-1.5">
              <Label htmlFor="username">Username</Label>
              <Input
                id="username"
                autoComplete="username"
                autoCapitalize="none"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                required
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
              />
            </div>
            {error && (
              <p role="alert" className="text-sm text-loss">
                {error}
              </p>
            )}
            <Button type="submit" variant="hero" size="lg" className="w-full" disabled={busy}>
              <LogIn /> {busy ? "Signing in…" : "Sign in"}
            </Button>
          </form>
        )}
      </div>
    </main>
  );
}
