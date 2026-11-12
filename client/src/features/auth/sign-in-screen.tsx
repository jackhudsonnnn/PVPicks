import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "./auth-provider";
import { TrustNotice } from "@/features/social/components/trust-notice";

export function AuthBrand() {
  return (
    <div className="text-center">
      <p className="font-display text-5xl font-black uppercase leading-none">
        <span className="text-primary">P2P</span>
        <span className="text-foreground">icks</span>
      </p>
      <p className="mt-2 text-sm text-muted-foreground">Bet with friends, not the house.</p>
    </div>
  );
}

export function SignInScreen() {
  const { logIn, notice, clearNotice } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const ok = await logIn(username, password);
      if (!ok) setError("That username and password don't match an account.");
    } catch {
      setError(
        "Sign-in isn't available in this browser. Try another browser or turn off private mode.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-sm space-y-8">
        <AuthBrand />

        {notice && (
          <p
            role="status"
            className="rounded-xl border border-primary/40 bg-primary/10 px-4 py-3 text-sm"
          >
            {notice}{" "}
            <button type="button" onClick={clearNotice} className="font-semibold underline">
              Dismiss
            </button>
          </p>
        )}

        <form onSubmit={submit} className="space-y-4 rounded-2xl border bg-surface p-5">
          <h1 className="font-display text-2xl font-extrabold uppercase">Sign in</h1>
          <div className="space-y-1.5">
            <Label htmlFor="username">Username</Label>
            <Input
              id="username"
              autoComplete="username"
              autoCapitalize="none"
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                setError(null);
              }}
              className="h-11 bg-background"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                setError(null);
              }}
              className="h-11 bg-background"
            />
          </div>
          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}
          <Button type="submit" variant="hero" size="lg" className="w-full" disabled={busy}>
            <LogIn /> Sign in
          </Button>
          <p className="text-center text-sm text-muted-foreground">
            New here?{" "}
            <Link
              to="/sign-up"
              className="font-semibold text-primary underline-offset-4 hover:underline"
            >
              Create an account
            </Link>
          </p>
        </form>

        <TrustNotice />
      </div>
    </main>
  );
}
