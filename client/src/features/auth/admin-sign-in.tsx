import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Logo } from "@/components/logo";
import { Spinner } from "@/components/loading";
import { BackendError, backendErrorMessage, isUnauthorized } from "@/lib/api-client";

/**
 * The admin's username and password form, at `/admin` in every mode. Nothing links here: on the LAN the admin opens
 * it by address, so guests only ever see the code sign-in. Signing in opens the admin workspace.
 */
export function AdminSignIn({
  login,
  notice,
}: {
  login: (username: string, password: string) => Promise<void>;
  notice: string | null;
}) {
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
          ? "This account is frozen."
          : isUnauthorized(failure)
            ? "That username and password don't match an admin account."
            : backendErrorMessage(failure),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-sm space-y-6">
        <Logo asLink={false} className="justify-center" />
        {notice && (
          <p role="status" className="text-sm">
            {notice}
          </p>
        )}
        <form
          onSubmit={(event) => void submit(event)}
          className="space-y-4 rounded-2xl border bg-surface p-5"
        >
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
          <Button asChild variant="ghost" size="sm" className="w-full">
            <Link to="/">I have a player code</Link>
          </Button>
        </form>
      </div>
    </main>
  );
}
