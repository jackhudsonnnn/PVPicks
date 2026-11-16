# Domain & privacy

This is a guide to important invariants, not a claim that every rule has an
implemented service. The detailed [domain rules](/reference/domain-rules)
and current decisions remain the working references.

## Bets and balances

- Money is integer cents; completed ledger writes must balance to zero.
- `No guess` is not a wager; a sports choice named `Pass` is a real choice.
- A bet is Active while accepting picks, Pending after lock, then Settled or
  Washed. Proposal does not automatically join.
- Active views must not expose other users' individual choices or
  participation; the caller may see their own and aggregate counts. Admin
  accounts cannot use player APIs or participate in bets.
- A remainder rule is snapshotted at lock. The Host rule selects one current
  host randomly and retains the chosen recipient, even if hosting changes later.
- Debt cap is off by default; changing it requires zero debt. Proposal, pick
  and lock transactions enforce the cap.

Every mode but Choose Their Fate is available over HTTP and in the frontend:
U2Pick, the score modes, the player modes and Scorcerer (NFL), with points
settlement and receipts. When a bet washes, at lock or at resolution, is listed
in the domain rules ("Wash triggers").

## Sports outcomes

- Sports bets resolve from the feed. Canceled, postponed or abandoned games,
  ties without a tie choice, suspensions longer than 48 hours and player props
  for players who did not play wash automatically; a Final settles after a
  short grace period, and an outage never settles anything.
- **Hosts have the final say.** They are trusted by the table and facilitate
  the money exchange, so any host may wash a sports bet or set its result,
  without giving a reason, until the table's points are settled, even on bets
  they joined. Overrides apply immediately, are announced in chat and can
  overrule an automatic wash or correct an earlier result. After points
  settlement every result is final.
- A subscriber table's host has the same powers over a Quick Pick copy; the
  copy then stops following the source table.

## Privacy

The current pick schema stores plaintext; an operator with infrastructure
access can inspect or modify stored data. Player and admin views must enforce
the Active-bet restrictions on the server. Dev bet reads enforce them.

## Tables and chat

- Hosts retain all permissions. Promotion clears prior restrictions; a host
  step-down requires another active host.
- A host may remove another member only after demoting them if they are a host.
  Removal is blocked while any bet is active or pending, or a resolved
  non-4fun bet awaits points settlement. Hosts can adjust member permissions
  instead. Records and receipts remain; chat read positions are retained.
- Table deletion has the same unsettled-bet gate. Members at deletion can find
  their read-only chat archive for 90 days; afterward it is hidden but its
  data remains pending a separate deletion policy. Open receipts continue
  counting toward debt and their receipt actions remain available.
- Remainder changes are rejected while a bet is Active, except an unchanged
  value is a no-op. Pending snapshots remain unchanged.
- Betting switches affect future proposals and Quick Pick fan-out, not existing
  bets. U2Pick proposal enforces them; fan-out remains planned.
- Enabling Quick Picks enables sharing. Disabling it disconnects incoming and
  outgoing subscriptions; stopping sharing or rotating the code disconnects
  incoming subscriptions. Existing copies remain. Subscription APIs and fan-out
  are not implemented.
- Text/GIF messages from authors blocked by the caller are hidden; bet cards
  and system messages follow their own visibility rules.
- Host-change system messages use subject IDs and resolve the current username
  or `Deleted user` at read time. Older free-text messages have not been
  retroactively repaired.

See the [API behavior notes](/reference/api-notes)
for detailed access and retry semantics.
