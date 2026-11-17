import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import QRCode from "qrcode";
import { Copy, KeyRound, UserPlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LoadingList } from "@/components/loading";
import { BackendError, backendErrorMessage } from "@/lib/api-client";
import type { User } from "@/features/people";
import { userKey } from "@/lib/query-keys";
import {
  createPlayer,
  issuePlayerCode,
  listPlayers,
  setupLink,
  type AdminPlayer,
  type SetupCode,
} from "./api";

/** The code just issued, shown once: the server never shows it again. */
type Shown = SetupCode & { username: string };

/**
 * Player accounts. There's no registration: the admin creates each account and hands the player a single-use setup
 * link (as a QR code), and issues a reset link when someone forgets their password.
 */
export function Players({ user }: { user: User }) {
  const queryClient = useQueryClient();
  const key = userKey(user.id, "admin-players");
  const players = useQuery({ queryKey: key, queryFn: ({ signal }) => listPlayers(signal) });
  const [username, setUsername] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [shown, setShown] = useState<Shown | null>(null);

  async function run(id: string, action: () => Promise<Shown>) {
    setBusy(id);
    setError(null);
    try {
      setShown(await action());
      await queryClient.invalidateQueries({ queryKey: key });
    } catch (failure) {
      setError(
        failure instanceof BackendError && failure.code === "username_taken"
          ? "That username is taken."
          : failure instanceof BackendError && failure.status === 400 && failure.detail
            ? failure.detail
            : backendErrorMessage(failure),
      );
    } finally {
      setBusy(null);
    }
  }

  function create(event: React.FormEvent) {
    event.preventDefault();
    void run("create", async () => {
      const created = await createPlayer(username);
      setUsername("");
      return created;
    });
  }

  return (
    <section className="space-y-4" aria-labelledby="players-heading">
      <h1 id="players-heading" className="font-display text-3xl font-bold uppercase">
        Players
      </h1>
      <form className="flex flex-wrap items-end gap-3" onSubmit={create}>
        <div className="min-w-48 flex-1 space-y-1.5">
          <Label htmlFor="new-player">New player's username</Label>
          <Input
            id="new-player"
            value={username}
            maxLength={15}
            autoComplete="off"
            onChange={(event) => setUsername(event.target.value)}
          />
        </div>
        <Button type="submit" disabled={!username.trim() || busy !== null}>
          <UserPlus /> Create
        </Button>
      </form>
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {shown && <SetupLinkCard shown={shown} onDone={() => setShown(null)} />}
      {players.isPending ? (
        <LoadingList label="Loading players" rows={3} rowClassName="h-12" />
      ) : players.isError ? (
        <p role="alert">{backendErrorMessage(players.error)}</p>
      ) : players.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No players yet.</p>
      ) : (
        <ul className="divide-y rounded-lg border bg-surface">
          {players.data.map((player) => (
            <li
              key={player.id}
              className="flex flex-wrap items-center justify-between gap-3 px-4 py-3"
            >
              <div>
                <p className="font-semibold">{player.username}</p>
                <p className="text-xs text-muted-foreground">{describe(player)}</p>
              </div>
              <Button
                variant="outline"
                size="sm"
                disabled={busy !== null || player.status !== "ACTIVE"}
                onClick={() =>
                  void run(player.id, async () => ({
                    ...(await issuePlayerCode(player.id)),
                    username: player.username,
                  }))
                }
              >
                <KeyRound /> {player.passwordSet ? "Reset link" : "New setup link"}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function describe(player: AdminPlayer) {
  const parts = [
    player.status === "FROZEN" ? "Frozen" : player.passwordSet ? "Signed up" : "Waiting for setup",
  ];
  if (player.codeExpiresAt)
    parts.push(`link valid until ${new Date(player.codeExpiresAt).toLocaleString()}`);
  return parts.join(" · ");
}

/** The setup or reset link as text and a QR code; it's gone once dismissed. */
function SetupLinkCard({ shown, onDone }: { shown: Shown; onDone: () => void }) {
  const link = setupLink(shown.setupCode);
  const [qr, setQr] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    let live = true;
    void QRCode.toString(link, { type: "svg", margin: 1 })
      .then((svg) => {
        if (live) setQr(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`);
      })
      .catch(() => {
        if (live) setQr(null);
      });
    return () => {
      live = false;
    };
  }, [link]);

  return (
    <div className="flex flex-wrap items-start gap-4 rounded-lg border-2 border-primary bg-surface p-4">
      {qr && (
        <img
          src={qr}
          alt={`Setup link for ${shown.username}`}
          className="size-40 rounded bg-white p-1"
        />
      )}
      <div className="min-w-0 flex-1 space-y-2">
        <p className="font-semibold">
          {shown.purpose === "RESET" ? "Reset link" : "Setup link"} for {shown.username}
        </p>
        <p className="text-sm text-muted-foreground">
          Works once, until {new Date(shown.expiresAt).toLocaleString()}. It won't be shown again:
          hand it over now, or issue a new one later.
        </p>
        <code className="block break-all rounded bg-muted px-2 py-1 text-xs">{link}</code>
        <div className="flex gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={() =>
              void navigator.clipboard.writeText(link).then(() => {
                setCopied(true);
              })
            }
          >
            <Copy /> {copied ? "Copied" : "Copy link"}
          </Button>
          <Button size="sm" onClick={onDone}>
            Done
          </Button>
        </div>
      </div>
    </div>
  );
}
