/** What happens when someone adds you to a table: join right away, get a request, or nothing. */
export type InviteMode = "open" | "request" | "closed";

export const INVITE_MODES: { id: InviteMode; label: string; description: string }[] = [
  { id: "open", label: "Open", description: "They can add you right away." },
  { id: "request", label: "Request", description: "You get a request to accept or decline." },
  { id: "closed", label: "Closed", description: "They can't add you." },
];
