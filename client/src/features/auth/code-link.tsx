import { useEffect, useRef, useState } from "react";
import { Logo } from "@/components/logo";
import { Spinner } from "@/components/loading";
import { Button } from "@/components/ui/button";
import { signInWithCode } from "./api";
import { isUnauthorized } from "@/lib/api-client";
import { codeProblem } from "./code-rules";

type CurrentUser = Awaited<ReturnType<typeof signInWithCode>>;

/**
 * Reads `/signin#<playerId>.<code>`: the player's id (stable across renames) and their code. The fragment never
 * reaches the server or proxy logs.
 */
function linkFromLocation(): { player: string; code: string } | null {
  if (typeof window === "undefined") return null;
  const [player, code, ...rest] = decodeURIComponent(window.location.hash.slice(1)).split(".");
  return player && code && rest.length === 0 ? { player, code } : null;
}

/**
 * Opening a player's QR link (`/signin#<playerId>.<code>`) signs them in straight away, signed in or out: scanning someone
 * else's code switches to that account. The code is removed from the address bar afterwards.
 */
export function CodeLinkSignIn({ onDone }: { onDone: (user: CurrentUser) => Promise<void> }) {
  const [link] = useState(linkFromLocation);
  const [error, setError] = useState<string | null>(
    link ? null : "This link is missing its code. Ask the admin for your QR code.",
  );
  const started = useRef(false);

  useEffect(() => {
    if (!link || started.current) return;
    started.current = true;
    void signInWithCode({ player: link.player }, link.code)
      .then(async (user) => {
        window.history.replaceState(null, "", "/");
        await onDone(user);
      })
      .catch((failure: unknown) =>
        setError(
          isUnauthorized(failure)
            ? "This QR code no longer works. Ask the admin for your current one."
            : codeProblem(failure),
        ),
      );
  }, [link, onDone]);

  return (
    <main className="grid min-h-dvh place-items-center bg-field px-4 py-10">
      <div className="w-full max-w-sm space-y-6 rounded-xl border bg-surface p-6 text-center shadow-sm">
        <Logo asLink={false} className="justify-center" />
        {error ? (
          <>
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
            <Button
              variant="outline"
              onClick={() => {
                window.history.replaceState(null, "", "/");
                window.location.reload();
              }}
            >
              Type a code instead
            </Button>
          </>
        ) : (
          <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <Spinner /> Signing you in…
          </p>
        )}
      </div>
    </main>
  );
}
