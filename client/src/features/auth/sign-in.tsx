import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { LogIn, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Logo } from "@/components/logo";
import { BackendError, backendErrorMessage, isUnauthorized } from "@/lib/api-client";
import { demoAccounts, type DemoAccount } from "./api";
import { codeProblem } from "./code-rules";
import { DemoPicker } from "./demo-picker";
import { LoadingScreen, Spinner } from "@/components/loading";

const frozenMessage = "This account is frozen. Contact an admin if you think that's a mistake.";

/**
 * Sign-in: the demo's account picker when the backend has the demo (dev). Otherwise (the LAN party) players type
 * their username and the code the admin gave them, or scan their QR code, which opens `/signin#<id>.<code>` instead
 * (the id, not the name, so a rename doesn't break it); a small Admin button
 * shows the admin's username and password form. Accounts are provisioned, so there is no sign-up.
 */
export function SignIn({
  login,
  loginAs,
  loginWithCode,
  notice,
  extras,
}: {
  login: (username: string, password: string) => Promise<void>;
  loginAs: (userId: string) => Promise<void>;
  loginWithCode: (username: string, code: string) => Promise<void>;
  notice: string | null;
  /** Shown beside the logo, e.g. the dev backend's Dev and docs icons. */
  extras?: React.ReactNode;
}) {
  const demo = useQuery({
    queryKey: ["demo-accounts", ""],
    queryFn: ({ signal }) => demoAccounts("", signal),
    retry: false,
    // An unreachable backend (e.g. restarting) isn't "no demo": keep checking, and the picker replaces the
    // password form once it answers. A 404 (no demo) resolves to null and stops.
    refetchInterval: (query) => (query.state.status === "error" ? 3000 : false),
  });
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [playerName, setPlayerName] = useState("");
  const [code, setCode] = useState("");
  const [admin, setAdmin] = useState(false);
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
            ? "That username and password don't match an admin account."
            : backendErrorMessage(failure),
      );
    } finally {
      setBusy(false);
    }
  }

  async function submitCode(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await loginWithCode(playerName, code);
      setCode("");
    } catch (failure) {
      setError(codeProblem(failure));
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

  // A failed demo check shows the password form (and keeps checking) rather than blocking sign-in. Until the
  // first answer, the same splash as the session check, so the two read as one step.
  if (demo.isPending) return <LoadingScreen label="Checking sign-in options" />;

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-sm space-y-6">
        <Logo asLink={false} className="justify-center" badge={extras} />
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
        ) : admin ? (
          <form onSubmit={submit} className="space-y-4 rounded-2xl border bg-surface p-5">
            <h1 className="font-display text-2xl font-extrabold uppercase">Admin sign-in</h1>
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
              {busy ? <Spinner /> : <LogIn />} Sign in
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="w-full"
              onClick={() => {
                setAdmin(false);
                setError(null);
              }}
            >
              I have a player code
            </Button>
          </form>
        ) : (
          <>
            <form onSubmit={submitCode} className="space-y-4 rounded-2xl border bg-surface p-5">
              <h1 className="font-display text-2xl font-extrabold uppercase">Sign in</h1>
              <p className="text-sm text-muted-foreground">
                Scan the QR code the admin shows you, or type your username and code here.
              </p>
              <div className="space-y-1.5">
                <Label htmlFor="player-username">Username</Label>
                <Input
                  id="player-username"
                  autoComplete="username"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  maxLength={15}
                  value={playerName}
                  onChange={(event) => setPlayerName(event.target.value)}
                  required
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="code">Your code</Label>
                <Input
                  id="code"
                  autoComplete="one-time-code"
                  autoCapitalize="characters"
                  autoCorrect="off"
                  spellCheck={false}
                  maxLength={9}
                  placeholder="K7QM"
                  className="h-14 text-center font-mono text-3xl uppercase tracking-[0.4em]"
                  value={code}
                  onChange={(event) => setCode(event.target.value.toUpperCase())}
                  required
                />
              </div>
              {error && (
                <p role="alert" className="text-sm text-loss">
                  {error}
                </p>
              )}
              <Button
                type="submit"
                variant="hero"
                size="lg"
                className="w-full"
                disabled={busy || !playerName.trim() || code.replace(/[\s-]/g, "").length !== 4}
              >
                {busy ? <Spinner /> : <LogIn />} Sign in
              </Button>
            </form>
            <div className="flex justify-center">
              <Button
                variant="ghost"
                size="sm"
                className="text-xs text-muted-foreground"
                onClick={() => {
                  setAdmin(true);
                  setError(null);
                }}
              >
                <ShieldCheck /> Admin
              </Button>
            </div>
          </>
        )}
      </div>
    </main>
  );
}
