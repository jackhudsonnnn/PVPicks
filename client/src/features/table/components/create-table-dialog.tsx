import { useState } from "react";
import { TrustNotice } from "@/features/social/components/trust-notice";
import { useNavigate } from "@tanstack/react-router";
import { Check, Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { myFriendIds, useAppStore, useGetUser } from "@/features/store";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { ScrollContainer } from "@/components/scroll-container";
import { TABLE_EMOJIS } from "../emojis";

export function CreateTableDialog({ trigger }: { trigger?: React.ReactNode }) {
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [emoji, setEmoji] = useState<string>(TABLE_EMOJIS[0]!);
  const [members, setMembers] = useState<string[]>([]);

  const toggle = (id: string) =>
    setMembers((m) => (m.includes(id) ? m.filter((x) => x !== id) : [...m, id]));

  function create() {
    const id = actions.createTable(name.trim() || "New Table", emoji, members);
    setOpen(false);
    setName("");
    setMembers([]);
    navigate({ to: "/tables/$tableId", params: { tableId: id } });
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        {trigger ?? (
          <Button
            variant="outline"
            size="sm"
            className="h-auto min-h-8 shrink whitespace-normal py-1.5 text-left leading-tight"
          >
            <Plus /> <span>New table</span>
          </Button>
        )}
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="font-display text-3xl font-black uppercase">
            New table
          </DialogTitle>
          <DialogDescription>A group chat where you can send bet tickets.</DialogDescription>
        </DialogHeader>
        <div className="space-y-5">
          <div className="flex gap-2">
            <Input
              placeholder="Table name"
              aria-label="Table name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={40}
              className="bg-surface"
            />
          </div>
          <div className="flex flex-wrap gap-1.5">
            {TABLE_EMOJIS.map((e) => (
              <button
                key={e}
                onClick={() => setEmoji(e)}
                className={cn(
                  "flex size-10 items-center justify-center rounded-lg border text-lg",
                  emoji === e ? "border-primary bg-primary/15" : "bg-surface",
                )}
              >
                {e}
              </button>
            ))}
          </div>
          <div>
            <p className="mb-2 font-mono text-[11px] font-bold uppercase tracking-widest text-muted-foreground">
              Add friends <span className="normal-case tracking-normal">(optional)</span>
            </p>
            <p className="mb-2 text-xs text-muted-foreground">
              You can add friends and followers later from the Host tab.
            </p>
            <ScrollContainer className="grid max-h-64 gap-1.5">
              {myFriendIds(state).map((id) => {
                const on = members.includes(id);
                return (
                  <button
                    key={id}
                    onClick={() => toggle(id)}
                    className={cn(
                      "flex items-center gap-3 rounded-lg border px-3 py-2 text-left text-sm",
                      on ? "border-primary bg-primary/10" : "bg-surface",
                    )}
                  >
                    <UserAvatar userId={id} size="sm" />
                    <span className="flex-1 font-semibold">{getUser(id).username}</span>
                    {on && <Check className="size-4 text-primary" />}
                  </button>
                );
              })}
            </ScrollContainer>
          </div>
          <TrustNotice />
        </div>
        <DialogFooter>
          <Button variant="hero" onClick={create}>
            Create table
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
