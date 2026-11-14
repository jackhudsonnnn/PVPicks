# Table rules

- Table screens live in `features/auth` (`backend-app.tsx`, `backend-chat.tsx`, `backend-table-members.tsx`); this folder holds shared table data only. The server enforces host permissions, member limits and settlement rules.
- `emojis.ts` is the table icon list. `gifs.ts` is the GIF catalog, keyed by the IDs the server accepts, so a live GIF search can replace it. `reactions.ts` (tested) holds the quick reactions and summarizes a message's reactions; the server toggles them.
