import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { UserPlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "./auth-provider";
import { AuthBrand } from "./sign-in-screen";
import {
  PASSWORD_MIN,
  USERNAME_MAX,
  USERNAME_MIN,
  signUpErrors,
  type SignUpErrors,
  type SignUpInput,
} from "./local-accounts";

const EMPTY: SignUpInput = { username: "", password: "", confirm: "" };

export function SignUpScreen() {
  const { signUp, takenUsernames } = useAuth();
  const [form, setForm] = useState<SignUpInput>(EMPTY);
  const [touched, setTouched] = useState<Partial<Record<keyof SignUpInput, boolean>>>({});
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<SignUpErrors>({});
  const [busy, setBusy] = useState(false);

  const live = signUpErrors(form, takenUsernames);
  const show = (k: keyof SignUpInput) => serverErrors[k] ?? ((submitted || touched[k]) && live[k]);

  const set = (k: keyof SignUpInput) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setForm((f) => ({ ...f, [k]: e.target.value }));
    setServerErrors((s) => ({ ...s, [k]: undefined }));
  };
  const blur = (k: keyof SignUpInput) => () => setTouched((t) => ({ ...t, [k]: true }));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitted(true);
    if (Object.keys(live).length) return;
    setBusy(true);
    try {
      setServerErrors(await signUp(form));
    } catch {
      setServerErrors({
        password:
          "Sign-up isn't available in this browser. Try another browser or turn off private mode.",
      });
    } finally {
      setBusy(false);
    }
  }

  const field = (
    k: keyof SignUpInput,
    label: string,
    props: React.ComponentProps<typeof Input> = {},
    hint?: string,
  ) => (
    <div className="space-y-1.5">
      <Label htmlFor={`su-${k}`}>{label}</Label>
      <Input
        id={`su-${k}`}
        value={form[k]}
        onChange={set(k)}
        onBlur={blur(k)}
        aria-invalid={!!show(k)}
        aria-describedby={show(k) ? `su-${k}-err` : undefined}
        className="h-11 bg-background"
        {...props}
      />
      {show(k) ? (
        <p id={`su-${k}-err`} className="text-xs text-loss">
          {show(k)}
        </p>
      ) : (
        hint && <p className="text-xs text-muted-foreground">{hint}</p>
      )}
    </div>
  );

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-sm space-y-8">
        <AuthBrand />

        <form onSubmit={submit} noValidate className="space-y-4 rounded-2xl border bg-surface p-5">
          <div>
            <h1 className="font-display text-2xl font-extrabold uppercase">Create account</h1>
            <p className="mt-1 text-xs text-muted-foreground">
              Saved on this device only for now, so it won't work on another phone or browser.
            </p>
          </div>
          {field(
            "username",
            "Username",
            { autoComplete: "username", autoCapitalize: "none", spellCheck: false },
            `${USERNAME_MIN}–${USERNAME_MAX} letters, numbers or underscores.`,
          )}
          {field(
            "password",
            "Password",
            { type: "password", autoComplete: "new-password" },
            `At least ${PASSWORD_MIN} characters.`,
          )}
          {field("confirm", "Confirm password", { type: "password", autoComplete: "new-password" })}
          <Button type="submit" variant="hero" size="lg" className="w-full" disabled={busy}>
            <UserPlus /> Create account
          </Button>
          <p className="text-center text-sm text-muted-foreground">
            Already have an account?{" "}
            <Link to="/" className="font-semibold text-primary underline-offset-4 hover:underline">
              Sign in
            </Link>
          </p>
        </form>
      </div>
    </main>
  );
}
