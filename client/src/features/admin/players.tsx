import { useEffect, useState } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PageEdge } from "@/components/client-pages";
import { ScrollContainer } from "@/components/scroll-container";
import QRCode from "qrcode";
import { Check, Copy, Eye, LockOpen, Search, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { LoadingList, Spinner } from "@/components/loading";
import { ConfirmAction } from "@/components/confirm-action";
import { BackendError, backendErrorMessage } from "@/lib/api-client";
import type { User } from "@/features/people";
import { userKey } from "@/lib/query-keys";
import { cn } from "@/lib/utils";
import {
  createPlayer,
  getPlayerCode,
  listPlayers,
  rotatePlayerCode,
  signInLink,
  type AdminPlayer,
} from "./api";
import { ago } from "./format";
import { NameAvatar } from "./avatar";
import { useDebounced } from "@/lib/use-debounced";
import { lockedById, useLockouts } from "./lockout-state";

/**
 * Player accounts: search by username, open one's audit, create a player, and show or rotate their sign-in code.
 * There's no registration: the admin creates each account and the player scans their QR code (or types the code).
 */
export function PlayerList({
  user,
  selected,
  onOpen,
  onClose,
}: {
  user: User;
  /** The selected player: their card is highlighted, their QR shown and their audit open. One rule, both ways. */
  selected: string | null;
  onOpen: (playerId: string) => void;
  /** Deselects: hides the QR and closes the audit. */
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const term = useDebounced(search.trim().toLowerCase());
  const key = userKey(user.id, "admin-players");
  const players = useInfiniteQuery({
    queryKey: [...key, term],
    queryFn: ({ pageParam, signal }) => listPlayers(pageParam, signal, term),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  const playerList = players.data?.pages.flatMap((page) => page.items) ?? [];
  const { lockouts, clear } = useLockouts(user);
  const locked = lockedById(lockouts.data?.players);
  const [creating, setCreating] = useState(false);
  const [username, setUsername] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await createPlayer(username);
      // The new card shows this code without another request.
      queryClient.setQueryData(codeKey(user.id, created.id), created.code);
      setUsername("");
      setCreating(false);
      toast.success(`${created.username} is ready. Their QR code is on their card.`);
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
      setBusy(false);
    }
  }

  return (
    <section className="space-y-3" aria-labelledby="players-heading">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="players-heading" className={SECTION_HEADING}>
          Players
        </h2>
        <div className="relative ml-auto w-full sm:w-64">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            aria-label="Search players"
            placeholder="Search usernames"
            className="h-9 pl-8"
            value={search}
            maxLength={15}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            setError(null);
            setCreating(true);
          }}
        >
          <UserPlus /> New player
        </Button>
      </div>
      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent>
          <DialogTitle>New player</DialogTitle>
          <DialogDescription>
            They get a sign-in code at once; show them the QR on their card.
          </DialogDescription>
          <form className="space-y-3" onSubmit={(event) => void create(event)}>
            <div className="space-y-1.5">
              <Label htmlFor="new-player">Username</Label>
              <Input
                id="new-player"
                value={username}
                maxLength={15}
                autoComplete="off"
                autoFocus
                onChange={(event) => setUsername(event.target.value)}
              />
            </div>
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
            <Button type="submit" className="w-full" disabled={!username.trim() || busy}>
              Create
            </Button>
          </form>
        </DialogContent>
      </Dialog>
      {players.isPending ? (
        <LoadingList label="Loading players" rows={1} rowClassName="h-32" />
      ) : players.isError ? (
        <p role="alert">{backendErrorMessage(players.error)}</p>
      ) : playerList.length === 0 ? (
        <p className="rounded-lg border bg-surface p-4 text-sm text-muted-foreground">
          {term ? "No usernames start with that." : "No players yet."}
        </p>
      ) : (
        <ScrollContainer
          isHorizontal
          isVisible={false}
          aria-label="Players"
          className="snap-x gap-3 pb-2 md:scrollbar-thin"
        >
          {playerList.map((player) => (
            <div
              key={player.id}
              className={cn(
                "relative flex w-60 shrink-0 snap-start flex-col gap-2 rounded-xl border bg-surface p-3",
                selected === player.id && "border-primary bg-primary/10",
              )}
            >
              <button
                type="button"
                className="flex min-w-0 items-center gap-2.5 text-left"
                aria-current={selected === player.id ? "true" : undefined}
                onClick={() => (selected === player.id ? onClose() : onOpen(player.id))}
              >
                <NameAvatar name={player.username} hue={player.hue} size="md" />
                <span className="min-w-0">
                  <span className="block truncate font-semibold">{player.username}</span>
                  <span className="block truncate text-xs text-muted-foreground">
                    {describe(player)}
                  </span>
                </span>
              </button>
              <div className="flex min-h-6 flex-wrap items-center gap-1.5">
                {player.status === "FROZEN" && <Chip tone="bad">Frozen</Chip>}
                {locked.has(player.id) && (
                  <>
                    <Chip tone="bad">Locked out</Chip>
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-6 px-2 text-xs"
                      disabled={clear.isPending}
                      aria-label={`Unlock ${player.username}`}
                      onClick={() => clear.mutate(player.id)}
                    >
                      <LockOpen /> Unlock
                    </Button>
                  </>
                )}
                {player.strikes > 0 && <Chip tone="warn">{player.strikes}/3</Chip>}
                {player.openReports > 0 && (
                  <Chip tone="warn">
                    {player.openReports} report{player.openReports === 1 ? "" : "s"}
                  </Chip>
                )}
              </div>
              <CodePanel
                user={user}
                player={player}
                shown={selected === player.id}
                onShow={() => onOpen(player.id)}
                onHide={onClose}
              />
            </div>
          ))}
          {players.hasNextPage && !players.isFetchingNextPage && (
            <PageEdge
              onMore={() => void players.fetchNextPage()}
              label="Load more"
              className="w-24 shrink-0 self-stretch"
            />
          )}
        </ScrollContainer>
      )}
    </section>
  );
}

/** The small uppercase section heading used across the admin pages. */
export const SECTION_HEADING =
  "font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground";

export function Chip({
  tone = "muted",
  children,
}: {
  tone?: "muted" | "warn" | "bad" | "good";
  children: React.ReactNode;
}) {
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center rounded px-1.5 py-0.5 font-mono text-[10px] font-bold uppercase",
        tone === "muted" && "bg-muted text-muted-foreground",
        tone === "warn" && "bg-[var(--viz-warning)]/15 text-[var(--viz-warning)]",
        tone === "bad" && "bg-loss/15 text-loss",
        tone === "good" && "bg-win/15 text-win",
      )}
    >
      {children}
    </span>
  );
}

function describe(player: AdminPlayer) {
  return player.lastSignInAt ? `Signed in ${ago(player.lastSignInAt)}` : "Never signed in";
}

const codeKey = (adminId: string, playerId: string) =>
  userKey(adminId, "admin-player-code", playerId);

/**
 * The player's sign-in QR code and code, blurred until shown (so it isn't readable over a shoulder or in a
 * screenshot). Shown means selected: showing it selects the player (and opens their audit), and tapping the shown QR
 * deselects them. Scanning opens `/signin#<id>.<code>`, which signs them in. Copy link works blurred too, without
 * showing the code. Rotating issues a new code and signs them out everywhere, so the old picture
 * stops working.
 */
function CodePanel({
  user,
  player,
  shown,
  onShow,
  onHide,
}: {
  user: User;
  player: AdminPlayer;
  shown: boolean;
  onShow: () => void;
  onHide: () => void;
}) {
  const queryClient = useQueryClient();
  const key = codeKey(user.id, player.id);
  const code = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getPlayerCode(player.id, signal),
    enabled: shown,
    staleTime: Infinity,
    retry: false,
  });
  const rotate = useMutation({
    mutationFn: () => rotatePlayerCode(player.id),
    onSuccess: (fresh) => {
      queryClient.setQueryData(key, fresh);
      onShow();
      toast.success(`${player.username} has a new code and was signed out everywhere.`);
      void queryClient.invalidateQueries({ queryKey: userKey(user.id, "admin-players") });
    },
    onError: (failure) => toast.error(backendErrorMessage(failure)),
  });
  const value = shown ? code.data : undefined;
  async function copyLink() {
    // Blurred, the code isn't loaded yet: fetch it for the link without showing it.
    const real = () =>
      queryClient
        .fetchQuery({
          queryKey: key,
          queryFn: ({ signal }) => getPlayerCode(player.id, signal),
          staleTime: Infinity,
        })
        .then((fetched) => signInLink(player.id, fetched));
    try {
      // Safari only allows a clipboard write started by the click itself, so hand it the pending text.
      if (typeof ClipboardItem !== "undefined" && navigator.clipboard?.write) {
        await navigator.clipboard.write([
          new ClipboardItem({
            "text/plain": real().then((text) => new Blob([text], { type: "text/plain" })),
          }),
        ]);
      } else {
        await navigator.clipboard?.writeText(await real());
      }
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch (failure) {
      toast.error(backendErrorMessage(failure));
    }
  }
  // Blurred, the QR encodes a placeholder: the real code only reaches the browser once shown.
  const link = signInLink(player.id, value ?? "XXXX");
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
  useEffect(() => setCopied(false), [value]);

  return (
    <div className="mt-auto space-y-2">
      <div className="relative mx-auto size-40 overflow-hidden rounded bg-white p-1">
        {qr && (
          <img
            src={qr}
            alt={value ? `Sign-in QR code for ${player.username}` : "Hidden sign-in QR code"}
            className={cn("size-full transition", !value && "scale-105 blur-md")}
          />
        )}
        {value ? (
          <button
            type="button"
            className="absolute inset-0 cursor-zoom-out"
            aria-label={`Hide ${player.username}'s code`}
            title="Tap to hide"
            onClick={onHide}
          />
        ) : (
          <button
            type="button"
            className="absolute inset-0 flex items-center justify-center"
            aria-label={`Show ${player.username}'s code`}
            onClick={onShow}
          >
            <span className="inline-flex items-center gap-1.5 rounded-full bg-black/75 px-3 py-1.5 text-xs font-semibold text-white">
              {shown && code.isFetching ? <Spinner /> : <Eye className="size-3.5" />} Show
            </span>
          </button>
        )}
      </div>
      <p
        className={cn(
          "text-center font-mono text-2xl font-bold tracking-[0.35em]",
          !value && "select-none text-muted-foreground",
        )}
        aria-label={value ? `Code ${value.split("").join(" ")}` : "Code hidden"}
      >
        {value ?? "••••"}
      </p>
      {code.isError && shown && (
        <p role="alert" className="text-center text-xs text-loss">
          {backendErrorMessage(code.error)}
        </p>
      )}
      {player.status === "FROZEN" && (
        <p className="text-center text-[11px] text-loss">Frozen: the code won't sign them in.</p>
      )}
      <div className="flex gap-1.5">
        <Button
          size="sm"
          variant="outline"
          className="flex-1"
          aria-label={`Copy ${player.username}'s sign-in link`}
          onClick={() => void copyLink()}
        >
          {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy link"}
        </Button>
        <ConfirmAction
          label="Rotate"
          title={`Give ${player.username} a new code?`}
          description="Their old QR code and code stop working, and they're signed out on every device. They sign back in with the new one."
          disabled={rotate.isPending}
          onConfirm={() => rotate.mutate()}
        />
      </div>
    </div>
  );
}
