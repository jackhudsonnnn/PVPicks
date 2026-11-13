# Domain & privacy

This is a guide to important invariants, not a claim that every rule has an
implemented service. The detailed <a href="/docs/source/reference/domain-rules.md" download>domain rules</a>
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
- Debt cap is off by default; changing it requires zero debt. Internal
  proposal, pick and lock transactions enforce the cap.

Bet transitions, picks and settlement services exist internally; their HTTP
adapters remain gated and are exercised only in tests.

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
  counting toward debt; receipt action HTTP adapters are gated.
- Remainder changes are rejected while a bet is Active, except an unchanged
  value is a no-op. Pending snapshots remain unchanged.
- Betting switches affect future proposals and Quick Pick fan-out, not existing
  bets. Internal U2Pick proposal enforces them; fan-out remains planned.
- Enabling Quick Picks enables sharing. Disabling it disconnects incoming and
  outgoing subscriptions; stopping sharing or rotating the code disconnects
  incoming subscriptions. Existing copies remain. Subscription APIs and fan-out
  are not implemented.
- Text/GIF messages from authors blocked by the caller are hidden; bet cards
  and system messages follow their own visibility rules.
- Host-change system messages use subject IDs and resolve the current username
  or `Deleted user` at read time. Older free-text messages have not been
  retroactively repaired.

See the <a href="/docs/source/reference/backend-contract-inventory.md" download>contract inventory</a>
for detailed access and retry semantics.
