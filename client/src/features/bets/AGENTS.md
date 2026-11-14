# Bets rules

- The server owns every bet rule: modes, locking, picks, results, washes, payouts and the leftover-cent rule. This folder holds only what the client needs to describe and draft bets. Why: one source of truth for money logic.
- `modes.ts` is the catalog the How to play guide shows (name, tagline, description, icon). Add a mode here when the server supports it; its fields and resolution live on the server.
- U2Pick drafts: `u2pick.ts` (tested) validates the question and options before sending and lists the settler rules; `components/u2pick-editor.tsx` renders the draft. Who may call a result is checked in `features/auth/bet-results.ts` and rechecked by the server.
- `ticker.ts` (tested) repeats games in whole cycles so the ticker's loop seam stays continuous; the strip itself is `features/auth/backend-ticker.tsx`.
