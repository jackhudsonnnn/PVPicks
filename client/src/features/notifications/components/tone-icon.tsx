import {
  AlertTriangle,
  HandCoins,
  Lock,
  MessageCircle,
  Sparkles,
  Trophy,
  Undo2,
  UserPlus,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";
import type { NotificationTone } from "../history";

const TONE_ICON: Record<NotificationTone, { icon: LucideIcon; className: string }> = {
  error: { icon: AlertTriangle, className: "text-loss" },
  chat: { icon: MessageCircle, className: "text-primary" },
  new: { icon: Sparkles, className: "text-primary" },
  locked: { icon: Lock, className: "text-status-pending" },
  won: { icon: Trophy, className: "text-win" },
  lost: { icon: Trophy, className: "text-loss" },
  resolved: { icon: Trophy, className: "text-status-resolved" },
  washed: { icon: Undo2, className: "text-status-washed" },
  invite: { icon: UserPlus, className: "text-invite" },
  settle: { icon: HandCoins, className: "text-primary" },
};

export function ToneIcon({ tone, className }: { tone: NotificationTone; className?: string }) {
  const { icon: Icon, className: color } = TONE_ICON[tone];
  return <Icon className={cn("size-4", color, className)} />;
}
