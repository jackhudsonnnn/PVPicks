import { Link } from "@tanstack/react-router";
import { UserPlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/**
 * Shown instead of "New table" or "All tickets" to someone with no friends, tables or tickets yet: P2Picks is
 * played with friends, so that's the first step.
 */
export function FindFriendsDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="font-display text-3xl font-black uppercase">
            Add friends first
          </DialogTitle>
          <DialogDescription>
            P2Picks is played with friends. Follow people you know and ask them to follow you back.
            Once you're friends you can start a table together, and its bets show up here as
            tickets.
          </DialogDescription>
        </DialogHeader>
        <Button asChild variant="hero" className="w-full">
          <Link to="/friends" onClick={() => onOpenChange(false)}>
            <UserPlus /> Find friends
          </Link>
        </Button>
      </DialogContent>
    </Dialog>
  );
}
