# Frontend-to-backend test crosswalk — unreviewed

Generated from the frontend test declarations before the No guess terminology
change. Except for the reviewed payout, picks, U2Pick, Quick Picks,
settle-receipts, reactions, unread, notification-history, feedback and admin-page sections below, line numbers and quoted
names are a historical snapshot, not a current test
inventory. Proposed JUnit names are targets, not implemented tests.
Review each remaining case against the current guides and, where unresolved,
the historical v3 plan; keep presentation, browser storage and
demo-only behavior in frontend tests. Parameterized declarations remain
parameterized. The historical snapshot enumerated 253 declarations.

## Reviewed classifications by source file

- `components/auto-page-sentinel.test.tsx`, `features/bets/ticker.test.ts`,
  `features/table/use-chat-window.test.tsx` and `test/app-routing.test.tsx`:
  retain all cases in the frontend. They exercise rendering, browser behavior
  or presentation buffering, not backend rules.
- `features/table/chat-window.test.ts`: retain all cases in the frontend for
  windowing and mounted-row limits. The "jumping to latest" case also needs a
  backend test that last-read positions never move backwards; it does not
  replace server cursor-pagination tests.
- `features/table/seating.test.ts`: retain all cases in the frontend for
  layout and chip rendering. Additionally test Active-pick privacy and
  post-lock reveal on the backend. The existing Active seating expectation
  exposes participant identities and must change during HTTP integration:
  the user chose to hide other participants until lock.
- `features/bets/wager.test.ts`: retain step controls and formatted display in
  the frontend. Port the 0.10-10.00 whole-cent validation boundary to the
  backend, with 4fun behavior covered separately. The default is a UI default
  unless the server contract makes it optional.
- `features/auth/demo-accounts.test.ts`: the literal demo credentials,
  sample-world identities, signup and browser storage keys remain prototype
  tests, not backend fixtures. Backend tests should separately cover
  case-insensitive trimmed username login, exact password matching, rejection
  of unknown users and wrong passwords, and unique user identities. Backend
  dev credentials must be generated locally rather than copied from the demo.
- `features/bets/ledger.test.ts`: port all three behavioral cases: a non-playing
  host receives the remainder but not a win, and a pending wager reduces the
  participant's available balance. Do not copy the prototype's starting
  balance into production policy without a separate decision.
- `features/bets/overtime.test.ts`: port mode eligibility, default-on and
  explicit-off cases to mode validation and outcome tests.
- `features/bets/info.test.ts`: retain row labels and presentation in the
  frontend. Independently test stored baselines, score/spread and stat
  calculations, possession metadata, and absence of outcomes before resolve.
- `features/bets/modes.test.ts`: port league-specific stat and mode validation,
  distinct options, distinct players and positive target; retain exact wording
  and formatting in the frontend. Test spread symmetry as a domain invariant.
- `features/social/follows.test.ts`: port mutual friendship, follow/unfollow,
  relationship reads and self-follow rejection. Relation labels remain UI.
- `features/social/invites.test.ts`: port the open/request/closed decisions,
  relationship direction, defaults and exclusion of members, pending requests
  and closed preferences; list presentation stays in the frontend.
- `features/social/safety.test.ts`: port asymmetric storage with bidirectional
  contact suppression, unblock and self-block prevention, report validation,
  and moderation ordering. Do not expose who blocked or reported someone.
- `features/feedback/feedback.test.ts` and `features/settings/profile.test.ts`:
  port all input boundaries and uniqueness rules; keep labels and form display
  in the frontend.
- `features/table/reactions.test.ts`: port add/remove, one reaction per user,
  replacement and isolation across users. Chip order and highlighting are UI.
- `features/table/unread.test.ts`: port read-position monotonicity and unread
  filtering (including own messages); retain visual divider behavior in UI.
- `features/table/hosts.test.ts`: port all authorization, membership, host
  continuity, per-member permission and active/pending deletion cases.
- `features/table/quick-picks.test.ts`: port code normalization, authorization,
  uniqueness and rotation, subscriptions, non-chaining, copy contents,
  disabled-mode filtering, lock eligibility, result propagation and host wash
  cases. Use persistent IDs rather than the prototype's composite string ID.
- `features/table/scenarios.test.ts`: port True/Settle calculation, 4fun and
  state exclusions and zero-sum. Replace object-identity cache tests with
  server cache invalidation/version tests.
- `features/settings/debt-cap.test.ts`: port default, non-offsetting debt,
  zero-debt edits, exact boundaries, 4fun exemption, create/pick/lock
  enforcement and concurrency. The local-storage reload case is frontend-only;
  test server-side persistence separately.
- `features/settings/deletion.test.ts`: port all three groups, including
  tombstoned ledger identity, personal-data cleanup, open picks and host
  handoff; ensure asynchronous projections also respect deletion.
- `features/auth/local-accounts.test.ts`: retain signup form and password
  confirmation in the frontend; public registration is deferred. Port
  username constraints/uniqueness, exact password verification, salted
  password hashing and username-only display for provisioned accounts.
- `features/notifications/history.test.ts`: port capped inbox ordering,
  stable cursor pages and unread/mark-read behavior; time labels stay in UI.
- `features/admin/admin-pages.test.ts`: port strike/freeze, strike clearing,
  notes and accurate audit net; synthetic random health samples are not server
  tests. Design health thresholds separately against real telemetry.
- `features/admin/analytics.test.ts`: port ledger zero-sum auditing, points
  moved excluding 4fun, outcome categories and deleted-user historical
  preservation. Prototype sample-world randomness and date-label parsing are
  not backend requirements; date grouping uses real timestamps and an
  explicitly defined timezone.
- `features/bets/payout.test.ts`: port each pot split, wash, No guess,
  sports-pass choice, host settlement, remainder allocation and zero-sum case.
  The prototype's missing-host fallback is not the backend contract: a missing
  host must fail the lock, not redirect the remainder.
  Add server tests for uniformly selecting among lock-time hosts, persisting
  the selection across retries and later host removal/deletion, and preserving
  zero-sum settlement when the chosen host is not a participant. Exercise
  concurrent locking and host changes against PostgreSQL.
- `features/bets/picks.test.ts` and `features/bets/u2pick.test.ts`: port
  selection/clear behavior, choice validation, settler authorization and
  normalization boundaries, including the newly allowed sports "Pass" choice.
  Proposing alone no longer enrolls the proposer.
- `features/table/settlements.test.ts`, `features/social/settle-receipts.test.ts`
  and `features/social/settle-groups.test.ts`: port payer/payee direction and
  authorization, separate historical receipts, open and closed nets, sent,
  dispute, forgiveness, retry timing, atomic grouped updates and opposing
  offsets. Keep formatted receipt display in the frontend.
- `features/social/settle-changes.test.ts`: port net-change resets, private
  notices, unchanged-net and completed-history preservation, stale revision
  rejection and individual-receipt isolation. The warning-opening interaction
  is frontend behavior; server concurrency guards must not depend on it.
- `features/notifications/events.test.ts`: port domain-event routing, own
  action exclusion, preference and block filtering, invitation and settlement
  notifications. Countdown-only updates and simulated diffs are not events;
  toast behavior remains frontend-only and v3 specifies inbox notifications.
- `features/store/reducer.test.ts`: port invalid-pick, settler authorization,
  feed-only game results, lock/remainder snapshot, active remainder conflict,
  Quick Pick copying, blocking and host settlement. The pure reducer's
  deterministic local IDs are not a server requirement. Replace its automatic
  sharing-on-enable expectation with the user's independent sharing decision.

These classifications cover every source file in the snapshot, but the
individual proposed JUnit names below have not yet been reconciled with these
decisions. A proposed name is not evidence that a case belongs on the backend
or that a test has been implemented.


## components/auto-page-sentinel.test.tsx

Source: [components/auto-page-sentinel.test.tsx](../../client/src/components/auto-page-sentinel.test.tsx).

- L11: loads once per page inside its own scroll area and re-arms for the next page → `ComponentsAutoPageSentinelTest.testLoadsOncePerPageInsideItsOwnScrollAreaAndReArmsForTheNextPage`

## features/admin/admin-pages.test.ts

Source: [features/admin/admin-pages.test.ts](../../client/src/features/admin/admin-pages.test.ts).

Individual review of all six current cases:

| Frontend case | Backend target |
|---|---|
| third strike freezes account | Atomically increment and freeze, revoke sessions, terminate realtime access and test simultaneous actions and idempotent retries. |
| strikes cap at three and can be cleared | Cap at three; clearing does not unfreeze. Explicit unfreeze at three is possible; another strike freezes again. Test ordering against concurrent actions. |
| blank notes are ignored | UI may ignore blank input; the API rejects blank notes explicitly without recording an action. |
| net matches resolved deltas | Sum the user's ledger entries in cents, including corrections and non-participant host remainders. Test washed/corrected bets and deleted-user identities. |
| p95 is never below p50 | Keep simulated health testing in the frontend; test the server using real measurements from the same observation window and distribution. |
| three errors in ten samples is degraded | Prototype's synthetic threshold is not a backend policy; define thresholds against real telemetry before testing. |

These are targets; the draft action schema test does not implement admin
authorization, actions, audit storage or analytics.

## features/admin/analytics.test.ts

Source: [features/admin/analytics.test.ts](../../client/src/features/admin/analytics.test.ts).

- L36: flags any resolved ticket whose points don't add to zero → `FeaturesAdminAnalyticsTest.testFlagsAnyResolvedTicketWhosePointsDonTAddToZero`
- L42: every sample player's history is zero-sum → `FeaturesAdminAnalyticsTest.testEverySamplePlayerSHistoryIsZeroSum`
- L48: points moved counts only the winners' side, and skips 4fun tickets → `FeaturesAdminAnalyticsTest.testPointsMovedCountsOnlyTheWinnersSideAndSkips4funTickets`
- L57: outcomes split wins, losses, washes and passes → `FeaturesAdminAnalyticsTest.testOutcomesSplitWinsLossesWashesAndPasses`
- L68: groups tickets by day with today last → `FeaturesAdminAnalyticsTest.testGroupsTicketsByDayWithTodayLast`
- L77: live samples stay in each player's range and keep the last 30 → `FeaturesAdminAnalyticsTest.testLiveSamplesStayInEachPlayerSRangeAndKeepTheLast30`
- L85: pro's deleted sample player shows as Deleted user but keeps their results → `FeaturesAdminAnalyticsTest.testProSDeletedSamplePlayerShowsAsDeletedUserButKeepsTheirResults`

## features/auth/demo-accounts.test.ts

Source: [features/auth/demo-accounts.test.ts](../../client/src/features/auth/demo-accounts.test.ts).

- L5: each demo account signs in with its own password → `FeaturesAuthDemoAccountsTest.testEachDemoAccountSignsInWithItsOwnPassword`
- L12: username ignores case and spaces, password does not → `FeaturesAuthDemoAccountsTest.testUsernameIgnoresCaseAndSpacesPasswordDoesNot`
- L17: wrong password or unknown user fails → `FeaturesAuthDemoAccountsTest.testWrongPasswordOrUnknownUserFails`
- L29: no two accounts share a username → `FeaturesAuthDemoAccountsTest.testNoTwoAccountsShareAUsername`
- L34: signing in as newbie / intermediate / pro shows that account's username, not Jack → `FeaturesAuthDemoAccountsTest.testSigningInAsNewbieIntermediateProShowsThatAccountSUsernameNotJack`
- L42: a signed-up account gets its own username in the brand-new world → `FeaturesAuthDemoAccountsTest.testASignedUpAccountGetsItsOwnUsernameInTheBrandNewWorld`
- L47: saved profile, inbox and blocks never share a key between accounts → `FeaturesAuthDemoAccountsTest.testSavedProfileInboxAndBlocksNeverShareAKeyBetweenAccounts`

## features/auth/local-accounts.test.ts

Source: [features/auth/local-accounts.test.ts](../../client/src/features/auth/local-accounts.test.ts).

- L17: a complete form has no errors → `FeaturesAuthLocalAccountsTest.testACompleteFormHasNoErrors`
- L21: the password has to be typed the same twice → `FeaturesAuthLocalAccountsTest.testThePasswordHasToBeTypedTheSameTwice`
- L27: passwords need at least 8 characters → `FeaturesAuthLocalAccountsTest.testPasswordsNeedAtLeast8Characters`
- L36: usernames are 3-15 letters, numbers or underscores → `FeaturesAuthLocalAccountsTest.testUsernamesAre315LettersNumbersOrUnderscores`
- L42: a taken username is rejected regardless of case → `FeaturesAuthLocalAccountsTest.testATakenUsernameIsRejectedRegardlessOfCase`
- L50: signs in with the right password only, username ignores case → `FeaturesAuthLocalAccountsTest.testSignsInWithTheRightPasswordOnlyUsernameIgnoresCase`
- L58: the person shown in the app is their username → `FeaturesAuthLocalAccountsTest.testThePersonShownInTheAppIsTheirUsername`

## features/bets/info.test.ts

Source: [features/bets/info.test.ts](../../client/src/features/bets/info.test.ts).

- L34: Either Or shows each player's initial, current and net stat → `FeaturesBetsInfoTest.testEitherOrShowsEachPlayerSInitialCurrentAndNetStat`
- L43: Give and Take adds the spread to the home score → `FeaturesBetsInfoTest.testGiveAndTakeAddsTheSpreadToTheHomeScore`
- L47: Choose Their Fate shows possession and starting yard line, no overtime row → `FeaturesBetsInfoTest.testChooseTheirFateShowsPossessionAndStartingYardLineNoOvertimeRow`
- L54: winning choice stays empty until resolved → `FeaturesBetsInfoTest.testWinningChoiceStaysEmptyUntilResolved`

## features/bets/ledger.test.ts

Source: [features/bets/ledger.test.ts](../../client/src/features/bets/ledger.test.ts).

- L17: a host who sat out still gets the leftover cent → `FeaturesBetsLedgerTest.testAHostWhoSatOutStillGetsTheLeftoverCent`
- L21: the leftover cent isn't counted as a win for someone who sat out → `FeaturesBetsLedgerTest.testTheLeftoverCentIsnTCountedAsAWinForSomeoneWhoSatOut`
- L26: a pending pot holds your wager → `FeaturesBetsLedgerTest.testAPendingPotHoldsYourWager`

## features/bets/modes.test.ts

Source: [features/bets/modes.test.ts](../../client/src/features/bets/modes.test.ts).

- L9: NBA games use basketball stats, NFL games use football stats → `FeaturesBetsModesTest.testNBAGamesUseBasketballStatsNFLGamesUseFootballStats`
- L15: Choose Their Fate asks about a drive in the NFL and a possession in the NBA → `FeaturesBetsModesTest.testChooseTheirFateAsksAboutADriveInTheNFLAndAPossessionInTheNBA`
- L22: U2Pick uses the proposer's own question and options → `FeaturesBetsModesTest.testU2PickUsesTheProposerSOwnQuestionAndOptions`
- L33: every mode gives every game at least two different choices and a question → `FeaturesBetsModesTest.testEveryModeGivesEveryGameAtLeastTwoDifferentChoicesAndAQuestion`
- L45: the spread choices are mirror images → `FeaturesBetsModesTest.testTheSpreadChoicesAreMirrorImages`
- L53: Either Or and King Of The Hill need two different players → `FeaturesBetsModesTest.testEitherOrAndKingOfTheHillNeedTwoDifferentPlayers`

## features/bets/overtime.test.ts

Source: [features/bets/overtime.test.ts](../../client/src/features/bets/overtime.test.ts).

- L5: stat and score modes offer it, on by default → `FeaturesBetsOvertimeTest.testStatAndScoreModesOfferItOnByDefault`
- L16: single-play and custom modes don't → `FeaturesBetsOvertimeTest.testSinglePlayAndCustomModesDonT`
- L21: keeps an off choice → `FeaturesBetsOvertimeTest.testKeepsAnOffChoice`

## features/bets/payout.test.ts

Source: [features/bets/payout.test.ts](../../client/src/features/bets/payout.test.ts).
Reviewed against the current 19 cases. The pure calculator now has focused
tests for pooled payouts, washes, No guess versus sports pass, all remainder
rules, zero-cent omission and 4fun. Amounts are integer cents. The source's
older "Pass" label in the sixth case means No guess, not a sports choice.
The named targets below remain a behavioral crosswalk, not a claim that those
exact test names or the settlement service exist. A pure table-settlement
calculator now tests receipt directions, host net and zero-net results; filtering
eligible bets and persisting receipts remain service test targets.

| Frontend case | Backend target / disposition |
|---|---|
| splits the losers' pot evenly: two Patriots winners each gain 0.5 | `PayoutTest.splitLoserPotAmongWinners` |
| a lone Bills winner takes the combined 2 points | `PayoutTest.singleWinnerReceivesCombinedPot` |
| washes when everyone picks the same selection | `PayoutTest.washWhenAllChoicesMatch` |
| washes when nobody picks the winning choice | `PayoutTest.washWhenNoOnePickedWinner` |
| washes when the outcome is outside the choices | `PayoutTest.washWhenOutcomeOutsideChoices` |
| players on Pass are not in the pot | `PayoutTest.excludeNoGuessFromPot` |
| pays a sports pass selection while excluding no guess | `PayoutTest.paySportsPassAndExcludeNoGuess` |
| winners get paid by the host, losers pay the host | `TableSettlementTest.routeTransfersThroughHost` |
| the host ends up with exactly their own result at the table | `TableSettlementTest.preserveHostNet` |
| ignores already-settled bets, other tables and unresolved bets | `TableSettlementTest.excludeSettledOtherTableAndUnresolvedBets` |
| host mode gives the odd cent to the host | `PayoutTest.hostReceivesRemainder` |
| proposer mode gives it to the proposer | `PayoutTest.proposerReceivesRemainder` |
| earliest mode gives it to the first winner to pick | `PayoutTest.earliestWinningPickReceivesRemainder` |
| random mode is repeatable for the same bet | `PayoutTest.randomRemainderIsDeterministic` |
| every mode sums to exactly 0 | `PayoutTest.everyRemainderModeIsZeroSum` |
| a missing host falls back to the earliest winner instead of a blank id | Do not port fallback; reject lock and surface the missing-host invariant violation. |
| a proposer who lost still gets the cent, and the total stays 0 | `PayoutTest.losingProposerReceivesRemainder` |
| a single winner takes the whole pot with nothing left over | `PayoutTest.singleWinnerLeavesNoRemainder` |
| random never gives one winner more than one extra cent | `PayoutTest.randomRemainderHasNoDuplicateRecipient` |

## features/bets/picks.test.ts

Source: [features/bets/picks.test.ts](../../client/src/features/bets/picks.test.ts).
Reviewed against all six current cases. No guess is a participation state,
not a pick row or a sports choice.

| Frontend case | Backend target |
|---|---|
| choosing an answer adds you to the bet | `PickServiceTest.choiceJoinsBet` |
| clearing your guess keeps you in the bet with no guess | `PickServiceTest.clearExistingPickRetainsNoGuessParticipation` |
| clearing when you never joined changes nothing | `PickServiceTest.clearBeforeJoiningDoesNotCreateParticipation` |
| changing your answer updates it in place | `PickServiceTest.changeExistingPick` |
| only the bet's choices or Pass count as a pick | `PickServiceTest.rejectUnknownChoiceAndAllowClear` |
| treats a sports pass choice as a pick, not no guess | `PickServiceTest.sportsPassIsAChoiceNotNoGuess` |

## features/bets/ticker.test.ts

Source: [features/bets/ticker.test.ts](../../client/src/features/bets/ticker.test.ts).

- L6: handles a feed with no games → `FeaturesBetsTickerTest.testHandlesAFeedWithNoGames`
- L10: fills at least 16 slots even with only one game → `FeaturesBetsTickerTest.testFillsAtLeast16SlotsEvenWithOnlyOneGame`
- L16: preserves whole modulo cycles at the loop seam → `FeaturesBetsTickerTest.testPreservesWholeModuloCyclesAtTheLoopSeam`
- L27: prioritizes live games without changing the feed → `FeaturesBetsTickerTest.testPrioritizesLiveGamesWithoutChangingTheFeed`
- L37: keeps every game in a feed larger than the minimum buffer → `FeaturesBetsTickerTest.testKeepsEveryGameInAFeedLargerThanTheMinimumBuffer`

## features/bets/u2pick.test.ts

Source: [features/bets/u2pick.test.ts](../../client/src/features/bets/u2pick.test.ts).
Reviewed against all ten current cases. The source's "someone on Pass" label
uses the No guess marker. Adapt fixtures to the user's decision that a proposer
does not automatically join.

| Frontend case | Backend target / disposition |
|---|---|
| anyone: every table member, but not outsiders | `U2PickTest.anyoneRequiresTableMembership` |
| me: only the proposer | `U2PickTest.onlyProposerForMeRule` |
| hosts: only hosts | `U2PickTest.onlyHostsForHostsRule` |
| non-participants: only members not in the pot | `U2PickTest.nonParticipantsMustBeOutsidePot` |
| non-participants: someone on Pass counts as not in the pot | `U2PickTest.noGuessMaySettleAsNonParticipant` |
| non-participants: if everyone is in the pot, hosts call it | `U2PickTest.hostsSettleWhenAllMembersAreInPot` |
| trims, drops blanks and duplicates | UI normalization helper remains frontend; backend validates normalized options rather than silently accepting duplicates. |
| needs a question and at least two different options | `U2PickTest.requireQuestionAndDistinctChoices` |
| allows sports pass choices but reserves the no-guess state | `U2PickTest.allowSportsPassAndReserveNoGuess` |
| allows at most 6 options | `U2PickTest.rejectMoreThanSixChoices` |

## features/bets/wager.test.ts

Source: [features/bets/wager.test.ts](../../client/src/features/bets/wager.test.ts).

- L6: defaults to 1.00 → `FeaturesBetsWagerTest.testDefaultsTo100`
- L10: steps by +0.01, +0.10 and +1.00 without float drift → `FeaturesBetsWagerTest.testStepsBy001010And100WithoutFloatDrift`
- L17: never goes above 10.00 → `FeaturesBetsWagerTest.testNeverGoesAbove1000`
- L24: allows 0.10 to 10.00 in whole cents only → `FeaturesBetsWagerTest.testAllows010To1000InWholeCentsOnly`
- L34: shows dollars-style points to the nearest hundredth → `FeaturesBetsWagerTest.testShowsDollarsStylePointsToTheNearestHundredth`

## features/feedback/feedback.test.ts

Source: [features/feedback/feedback.test.ts](../../client/src/features/feedback/feedback.test.ts).

Individual review of all three current cases:

| Frontend case | Backend target |
|---|---|
| offers five feedback kinds | Preserve labels in the UI; reject unknown kinds in storage and the API. |
| needs 10 to 1000 characters | Validate 10–1000 UTF-16 code units after trimming in the submission service, including whitespace and supplementary Unicode boundaries; V12 provides only basic database length checks. |
| optional link must be a web address | Trim and accept an empty link as absent; otherwise require the prototype's case-insensitive HTTP(S) shape without whitespace. Never fetch submitted links. |

The schema and contract tests cover basic storage and request shapes, not a
submission service or authorization.

## features/notifications/events.test.ts

Source: [features/notifications/events.test.ts](../../client/src/features/notifications/events.test.ts).

- L36: reports a ticket locking from Active to Pending → `FeaturesNotificationsEventsTest.testReportsATicketLockingFromActiveToPending`
- L45: ignores countdown ticks that don't change status → `FeaturesNotificationsEventsTest.testIgnoresCountdownTicksThatDonTChangeStatus`
- L54: skips my own new ticket but reports someone else's → `FeaturesNotificationsEventsTest.testSkipsMyOwnNewTicketButReportsSomeoneElseS`
- L65: reports new chat from others, not from me → `FeaturesNotificationsEventsTest.testReportsNewChatFromOthersNotFromMe`
- L89: master switch off silences everything → `FeaturesNotificationsEventsTest.testMasterSwitchOffSilencesEverything`
- L95: 'my tickets' scope skips tickets I'm not in; 'all' includes them → `FeaturesNotificationsEventsTest.testMyTicketsScopeSkipsTicketsIMNotInAllIncludesThem`
- L101: muted tables and the table you're viewing don't toast chats → `FeaturesNotificationsEventsTest.testMutedTablesAndTheTableYouReViewingDonTToastChats`
- L107: chats from people you blocked never notify → `FeaturesNotificationsEventsTest.testChatsFromPeopleYouBlockedNeverNotify`
- L112: error toggle controls error toasts → `FeaturesNotificationsEventsTest.testErrorToggleControlsErrorToasts`
- L123: a new request for me becomes an invite event → `FeaturesNotificationsEventsTest.testANewRequestForMeBecomesAnInviteEvent`
- L135: being added straight in is a joined invite; accepting my own request is not → `FeaturesNotificationsEventsTest.testBeingAddedStraightInIsAJoinedInviteAcceptingMyOwnRequestIsNot`
- L150: the Table invites switch controls them → `FeaturesNotificationsEventsTest.testTheTableInvitesSwitchControlsThem`
- L164: notifies both people once when a pending $5 payment becomes $8, but not outsiders → `FeaturesNotificationsEventsTest.testNotifiesBothPeopleOnceWhenAPending5PaymentBecomes8ButNotOutsiders`
- L185: tells you when someone asks you to forgive what they owe → `FeaturesNotificationsEventsTest.testTellsYouWhenSomeoneAsksYouToForgiveWhatTheyOwe`
- L193: tells the payer when they're forgiven → `FeaturesNotificationsEventsTest.testTellsThePayerWhenTheyReForgiven`

## features/notifications/history.test.ts

Source: [features/notifications/history.test.ts](../../client/src/features/notifications/history.test.ts).

Individual review of all three current cases (server behavior remains unimplemented):

| Frontend case | Backend target |
|---|---|
| newest first, dropping oldest past cap | Retain at most 200 per user; serialize concurrent inserts and cap enforcement. |
| cursor pages are stable when new items arrive | Use opaque keyset cursors over stable recipient order; page size 15, with insert/clear/eviction concurrency tests. |
| count and clear unread | Count the caller's visible unread items and mark the current inbox read atomically. Also test the user's chat-position decision, delayed event delivery and cross-session races. |

## features/settings/debt-cap.test.ts

Source: [features/settings/debt-cap.test.ts](../../client/src/features/settings/debt-cap.test.ts).

- L22: is off by default → `FeaturesSettingsDebtCapTest.testIsOffByDefault`
- L26: adds negative table True balances without offsetting positive tables → `FeaturesSettingsDebtCapTest.testAddsNegativeTableTrueBalancesWithoutOffsettingPositiveTables`
- L31: adds negative person settle-up nets without offsetting money owed by other people → `FeaturesSettingsDebtCapTest.testAddsNegativePersonSettleUpNetsWithoutOffsettingMoneyOwedByOtherPeople`
- L39: blocks every limit change with any debt, even if net positive → `FeaturesSettingsDebtCapTest.testBlocksEveryLimitChangeWithAnyDebtEvenIfNetPositive`
- L46: permits whole-point edits and disabling only at zero debt → `FeaturesSettingsDebtCapTest.testPermitsWholePointEditsAndDisablingOnlyAtZeroDebt`
- L53: accepts exact-cap wagers and exempts 4fun → `FeaturesSettingsDebtCapTest.testAcceptsExactCapWagersAndExempts4fun`
- L59: enforces caps on proposals, joins and picks → `FeaturesSettingsDebtCapTest.testEnforcesCapsOnProposalsJoinsAndPicks`
- L66: locks simultaneous bets sequentially and passes picks over the cap → `FeaturesSettingsDebtCapTest.testLocksSimultaneousBetsSequentiallyAndPassesPicksOverTheCap`
- L73: saved limits reload even while debt exists → `FeaturesSettingsDebtCapTest.testSavedLimitsReloadEvenWhileDebtExists`

## features/settings/deletion.test.ts

Source: [features/settings/deletion.test.ts](../../client/src/features/settings/deletion.test.ts).

- L57: keeps past results so the ledger still adds up, under 'Deleted user' → `FeaturesSettingsDeletionTest.testKeepsPastResultsSoTheLedgerStillAddsUpUnderDeletedUser`
- L67: drops their picks on open tickets and their chats, follows, reactions and blocks → `FeaturesSettingsDeletionTest.testDropsTheirPicksOnOpenTicketsAndTheirChatsFollowsReactionsAndBlocks`
- L78: removes them from tables and hands hosting to the next member → `FeaturesSettingsDeletionTest.testRemovesThemFromTablesAndHandsHostingToTheNextMember`

## features/settings/profile.test.ts

Source: [features/settings/profile.test.ts](../../client/src/features/settings/profile.test.ts).

- L5: usernames are 3–15 characters → `FeaturesSettingsProfileTest.testUsernamesAre315Characters`
- L12: only letters, numbers and underscores → `FeaturesSettingsProfileTest.testOnlyLettersNumbersAndUnderscores`
- L17: are unique ignoring case → `FeaturesSettingsProfileTest.testAreUniqueIgnoringCase`

## features/social/follows.test.ts

Source: [features/social/follows.test.ts](../../client/src/features/social/follows.test.ts).

- L15: people who follow each other are friends → `FeaturesSocialFollowsTest.testPeopleWhoFollowEachOtherAreFriends`
- L21: lists following and followers, friends included → `FeaturesSocialFollowsTest.testListsFollowingAndFollowersFriendsIncluded`
- L26: describes the relation from my side → `FeaturesSocialFollowsTest.testDescribesTheRelationFromMySide`
- L33: following back makes a friend, unfollowing undoes it → `FeaturesSocialFollowsTest.testFollowingBackMakesAFriendUnfollowingUndoesIt`
- L39: you can't follow yourself → `FeaturesSocialFollowsTest.testYouCanTFollowYourself`

## features/social/invites.test.ts

Source: [features/social/invites.test.ts](../../client/src/features/social/invites.test.ts).

- L10: friends with Open join right away → `FeaturesSocialInvitesTest.testFriendsWithOpenJoinRightAway`
- L16: friends with Request get a request → `FeaturesSocialInvitesTest.testFriendsWithRequestGetARequest`
- L22: the Following setting applies when you follow the host but they don't follow back → `FeaturesSocialInvitesTest.testTheFollowingSettingAppliesWhenYouFollowTheHostButTheyDonTFollowBack`
- L29: someone who doesn't follow the host can never be added → `FeaturesSocialInvitesTest.testSomeoneWhoDoesnTFollowTheHostCanNeverBeAdded`
- L34: defaults: friends Open, following Request → `FeaturesSocialInvitesTest.testDefaultsFriendsOpenFollowingRequest`
- L42: friends are the mutual follows; followers include friends → `FeaturesSocialInvitesTest.testFriendsAreTheMutualFollowsFollowersIncludeFriends`
- L48: skips members, pending requests and closed settings → `FeaturesSocialInvitesTest.testSkipsMembersPendingRequestsAndClosedSettings`

## features/social/safety.test.ts

Source: [features/social/safety.test.ts](../../client/src/features/social/safety.test.ts).

- L5: blocks one way but counts for both people → `FeaturesSocialSafetyTest.testBlocksOneWayButCountsForBothPeople`
- L12: unblocking removes it, and you can't block yourself → `FeaturesSocialSafetyTest.testUnblockingRemovesItAndYouCanTBlockYourself`
- L22: needs a reason, and a note when the reason is Other → `FeaturesSocialSafetyTest.testNeedsAReasonAndANoteWhenTheReasonIsOther`
- L28: can't report yourself or write more than 500 characters → `FeaturesSocialSafetyTest.testCanTReportYourselfOrWriteMoreThan500Characters`
- L34: review queue shows unhandled reports first, newest first → `FeaturesSocialSafetyTest.testReviewQueueShowsUnhandledReportsFirstNewestFirst`

## features/social/settle-changes.test.ts

Source: [features/social/settle-changes.test.ts](../../client/src/features/social/settle-changes.test.ts).

- L12: requires opening the warning before sending or asking for forgiveness → `FeaturesSocialSettleChangesTest.testRequiresOpeningTheWarningBeforeSendingOrAskingForForgiveness`
- L18: requires a fresh review for another warning even with the same ledger revision → `FeaturesSocialSettleChangesTest.testRequiresAFreshReviewForAnotherWarningEvenWithTheSameLedgerRevision`
- L22: resets a sent $5 claim after adding %s, leaving net %s → `FeaturesSocialSettleChangesTest.testResetsASent5ClaimAfterAddingSLeavingNetS` (parameterized)
- L31: cancels an open forgiveness request on a balance change → `FeaturesSocialSettleChangesTest.testCancelsAnOpenForgivenessRequestOnABalanceChange`
- L37: preserves pending claims when the net is unchanged → `FeaturesSocialSettleChangesTest.testPreservesPendingClaimsWhenTheNetIsUnchanged`
- L42: never resets paid or forgiven history → `FeaturesSocialSettleChangesTest.testNeverResetsPaidOrForgivenHistory`
- L48: does not report successful full-net payment as cancellation → `FeaturesSocialSettleChangesTest.testDoesNotReportSuccessfulFullNetPaymentAsCancellation`
- L51: rejects a stale full-net confirmation without clearing any lines → `FeaturesSocialSettleChangesTest.testRejectsAStaleFullNetConfirmationWithoutClearingAnyLines`
- L57: preserves unrelated in-flight receipts when an individual receipt is confirmed → `FeaturesSocialSettleChangesTest.testPreservesUnrelatedInFlightReceiptsWhenAnIndividualReceiptIsConfirmed`

## features/social/settle-groups.test.ts

Source: [features/social/settle-groups.test.ts](../../client/src/features/social/settle-groups.test.ts).

- L19: groups tables into one net with a signed per-table breakdown → `FeaturesSocialSettleGroupsTest.testGroupsTablesIntoOneNetWithASignedPerTableBreakdown`
- L25: nets repeated settlements at the same table in cents → `FeaturesSocialSettleGroupsTest.testNetsRepeatedSettlementsAtTheSameTableInCents`
- L28: a sent payment in the opposite direction cannot block paying the new net → `FeaturesSocialSettleGroupsTest.testASentPaymentInTheOppositeDirectionCannotBlockPayingTheNewNet`
- L33: confirming the full net closes all lines including opposite directions → `FeaturesSocialSettleGroupsTest.testConfirmingTheFullNetClosesAllLinesIncludingOppositeDirections`
- L38: only the net payer can mark the entire amount sent → `FeaturesSocialSettleGroupsTest.testOnlyTheNetPayerCanMarkTheEntireAmountSent`
- L46: forgiveness clears the full net, never a single contributing amount → `FeaturesSocialSettleGroupsTest.testForgivenessClearsTheFullNetNeverASingleContributingAmount`
- L51: applies one forgiveness request to all contributing lines → `FeaturesSocialSettleGroupsTest.testAppliesOneForgivenessRequestToAllContributingLines`
- L56: a zero-net group can clear without any payment → `FeaturesSocialSettleGroupsTest.testAZeroNetGroupCanClearWithoutAnyPayment`

## features/social/settle-receipts.test.ts

Source: [features/social/settle-receipts.test.ts](../../client/src/features/social/settle-receipts.test.ts).

Individual review of all five current cases (server behavior remains unimplemented):

| Frontend case | Backend target |
|---|---|
| repeated settlements at one table remain separate dated rows | Persist distinct immutable settlement and receipt identities; sort by actual timestamps rather than prototype clock labels. |
| sent stays outstanding until received | Include sent amounts in the open net; only confirmation closes a paid receipt. Test mixed directions in integer cents. |
| cannot sign the other person's sent or received column | Authorize sent by payer and confirmation by payee; reject stale revisions. |
| completed history is accessible without affecting the net | Retain authorized paid/forgiven receipts in history and exclude them from open balances. |
| not received resets only that row; forgiveness clears only that amount | An individual dispute returns that receipt to unpaid and individual forgiveness closes that receipt. Separately test whole-net group actions and net-change reconciliation. |

## features/store/reducer.test.ts

Source: [features/store/reducer.test.ts](../../client/src/features/store/reducer.test.ts).

- L61: is pure: the same action on the same state gives the same messages and ids → `FeaturesStoreReducerTest.testIsPureTheSameActionOnTheSameStateGivesTheSameMessagesAndIds`
- L70: ignores a pick that isn't one of the bet's choices → `FeaturesStoreReducerTest.testIgnoresAPickThatIsnTOneOfTheBetSChoices`
- L76: only the proposer's chosen settlers can call a U2Pick → `FeaturesStoreReducerTest.testOnlyTheProposerSChosenSettlersCanCallAU2Pick`
- L102: game bets can't be called by a person; they settle from the game result → `FeaturesStoreReducerTest.testGameBetsCanTBeCalledByAPersonTheySettleFromTheGameResult`
- L121: locks a bet at zero and posts the lock line with the given time → `FeaturesStoreReducerTest.testLocksABetAtZeroAndPostsTheLockLineWithTheGivenTime`
- L128: fixes the leftover-cent rule on a bet when it locks → `FeaturesStoreReducerTest.testFixesTheLeftoverCentRuleOnABetWhenItLocks`
- L135: hosts can't change leftover cents while a bet takes picks → `FeaturesStoreReducerTest.testHostsCanTChangeLeftoverCentsWhileABetTakesPicks`
- L143: turning on Quick Picks always shares the table with a code → `FeaturesStoreReducerTest.testTurningOnQuickPicksAlwaysSharesTheTableWithACode`
- L154: a Quick Pick source's new bet posts a copy at a subscribed table → `FeaturesStoreReducerTest.testAQuickPickSourceSNewBetPostsACopyAtASubscribedTable`
- L166: blocking someone removes follows both ways → `FeaturesStoreReducerTest.testBlockingSomeoneRemovesFollowsBothWays`
- L172: only a host can settle points, and it marks the table's results settled → `FeaturesStoreReducerTest.testOnlyAHostCanSettlePointsAndItMarksTheTableSResultsSettled`

## features/table/chat-window.test.ts

Source: [features/table/chat-window.test.ts](../../client/src/features/table/chat-window.test.ts).

- L12: opens around the oldest unread, not thousands of unread messages → `FeaturesTableChatWindowTest.testOpensAroundTheOldestUnreadNotThousandsOfUnreadMessages`
- L16: opens caught-up and empty chats at the latest window → `FeaturesTableChatWindowTest.testOpensCaughtUpAndEmptyChatsAtTheLatestWindow`
- L21: jumping to latest skips the gap and marks all messages read → `FeaturesTableChatWindowTest.testJumpingToLatestSkipsTheGapAndMarksAllMessagesRead`
- L28: prunes the opposite end while paging in either direction → `FeaturesTableChatWindowTest.testPrunesTheOppositeEndWhilePagingInEitherDirection`
- L42: can traverse thousands of messages both ways without exceeding 60 mounted rows → `FeaturesTableChatWindowTest.testCanTraverseThousandsOfMessagesBothWaysWithoutExceeding60MountedRows`
- L55: bounds new arrivals and never pages outside history → `FeaturesTableChatWindowTest.testBoundsNewArrivalsAndNeverPagesOutsideHistory`

## features/table/hosts.test.ts

Source: [features/table/hosts.test.ts](../../client/src/features/table/hosts.test.ts).

- L27: only hosts get host powers → `FeaturesTableHostsTest.testOnlyHostsGetHostPowers`
- L34: a host can remove others but not themselves → `FeaturesTableHostsTest.testAHostCanRemoveOthersButNotThemselves`
- L39: the last host can't be demoted; with two hosts either can step down → `FeaturesTableHostsTest.testTheLastHostCanTBeDemotedWithTwoHostsEitherCanStepDown`
- L47: everyone can start bets until a host limits bets to hosts → `FeaturesTableHostsTest.testEveryoneCanStartBetsUntilAHostLimitsBetsToHosts`
- L55: turning off U2Pick blocks custom bets for everyone but leaves other modes alone → `FeaturesTableHostsTest.testTurningOffU2PickBlocksCustomBetsForEveryoneButLeavesOtherModesAlone`
- L63: hosts can only add people who aren't already members → `FeaturesTableHostsTest.testHostsCanOnlyAddPeopleWhoArenTAlreadyMembers`
- L70: members can do everything until a host turns something off → `FeaturesTableHostsTest.testMembersCanDoEverythingUntilAHostTurnsSomethingOff`
- L79: turning a permission back on removes the limit → `FeaturesTableHostsTest.testTurningAPermissionBackOnRemovesTheLimit`
- L84: no bets permission means they can't start bets either → `FeaturesTableHostsTest.testNoBetsPermissionMeansTheyCanTStartBetsEither`
- L89: hosts can't be limited and only hosts can limit → `FeaturesTableHostsTest.testHostsCanTBeLimitedAndOnlyHostsCanLimit`
- L99: clearing limits drops them for that member only → `FeaturesTableHostsTest.testClearingLimitsDropsThemForThatMemberOnly`
- L104: people who left have no permissions → `FeaturesTableHostsTest.testPeopleWhoLeftHaveNoPermissions`
- L110: a member with bets on still can't start a bet when only hosts can, but can pick → `FeaturesTableHostsTest.testAMemberWithBetsOnStillCanTStartABetWhenOnlyHostsCanButCanPick`
- L121: lets a host delete when all bets are resolved or washed → refine:
  reject if resolved non-4fun points are unsettled, otherwise allow subject to
  the Active/Pending rule. Test zero-net settlement, washed and 4fun cases.
- L124: blocks deleting while a bet is active → retain as a server test.
- L127: blocks deleting while a bet is pending → retain as a server test.
- L130: never lets a non-host delete → retain as a server authorization test.

Deletion also needs server tests for settlement and membership races,
read-only archive visibility, preserved receipt actions, subscription removal,
and surviving Quick Pick copies and delayed propagation. These are targets,
not implemented behavior.

## features/table/quick-picks.test.ts

Source: [features/table/quick-picks.test.ts](../../client/src/features/table/quick-picks.test.ts).

Individual review of all 15 current cases (server targets unless noted):

| Frontend case | Backend target or disposition |
|---|---|
| codes are 6 readable characters | Generate unique six-character codes from the agreed alphabet; persist one per table even when not sharing. |
| match regardless of case, spaces and dashes | Normalize input before lookup; reject invalid or unmatched codes. |
| finds the sharing table by code | Subscribe only to a currently sharing source; do not expose source details through an invalid code. |
| needs a host | Authorize subscription using the subscriber table's current hosts. |
| needs Quick Picks on | Reject a subscription if the subscriber has the feature disabled. |
| rejects an old code after the source made a new one | Rotation changes the code, disconnects existing subscribers, and rejects new subscriptions using the old code. |
| rejects its own code and duplicates | Reject self-subscription and duplicate directed relationships; database constraints also enforce both. |
| lists subscribers only while Quick Picks is on | Disabling Quick Picks stops sharing and removes incoming and outgoing subscription relationships. |
| exact copies with no picks, labelled with the source | Copy the proposal fields and source reference with no participants; use a persistent server ID, not the prototype's composite string. |
| copies are never shared again | Only originals fan out; enforce across retries and asynchronous delivery. |
| mutual subscriptions do not loop | Reciprocal subscription rows are valid, but copied bets must not fan out or loop. |
| skip U2Picks at a table with U2Pick off | Exclude custom bets from backfill and new fan-out when the target has U2Pick disabled. |
| skip bets that already locked | Backfill only eligible Active originals, with a concurrency test around locking. |
| pending copies follow the source | Propagate the original's result to eligible copies once; duplicate or reordered events require idempotent handling. |
| only a host at the copy's table can override before points are settled | Permit wash while Pending or after resolution but before table points settlement; reject non-hosts and other states. A resolved wash requires atomic compensating ledger entries. |

## features/table/reactions.test.ts

Source: [features/table/reactions.test.ts](../../client/src/features/table/reactions.test.ts).

Individual review of all five current cases:

| Frontend case | Backend target or disposition |
|---|---|
| add a reaction, then tap it again to remove | The adapter uses idempotent PUT to set and DELETE to remove; do not make a retried PUT toggle twice. |
| several people stack the same emoji | Store one row per user and message, allowing different users to use the same emoji. |
| a new emoji replaces the user's old one | Atomically replace only the caller's row. |
| switching leaves other people's reactions alone | Verify isolation and permission checks, including concurrent callers. |
| order chips by count and flag mine | Keep chip sorting and display in the frontend; the server returns authorized reaction data. |

## features/table/scenarios.test.ts

Source: [features/table/scenarios.test.ts](../../client/src/features/table/scenarios.test.ts).

- L40: settle keeps only stored results and sums to 0 → `FeaturesTableScenariosTest.testSettleKeepsOnlyStoredResultsAndSumsTo0`
- L46: true = settle minus wagers in pending pots → `FeaturesTableScenariosTest.testTrueSettleMinusWagersInPendingPots`
- L54: 4fun, active and other-table tickets are left out → `FeaturesTableScenariosTest.test4funActiveAndOtherTableTicketsAreLeftOut`
- L68: reuses the result until the bets change → `FeaturesTableScenariosTest.testReusesTheResultUntilTheBetsChange`

## features/table/seating.test.ts

Source: [features/table/seating.test.ts](../../client/src/features/table/seating.test.ts).

- L10: with more than 8 members, seats 7 and the rest go to the 'others' spot → `FeaturesTableSeatingTest.testWithMoreThan8MembersSeats7AndTheRestGoToTheOthersSpot`
- L16: 9 members: 7 seated plus '2 others' → `FeaturesTableSeatingTest.test9Members7SeatedPlus2Others`
- L21: 8 or fewer members all get a seat and no 'others' spot → `FeaturesTableSeatingTest.test8OrFewerMembersAllGetASeatAndNoOthersSpot`
- L26: you always take the first seat → `FeaturesTableSeatingTest.testYouAlwaysTakeTheFirstSeat`
- L30: only selected active bet participants get seats, not even you otherwise → `FeaturesTableSeatingTest.testOnlySelectedActiveBetParticipantsGetSeatsNotEvenYouOtherwise`
- L40: includes Pass participants in pending bets, with you first if participating → `FeaturesTableSeatingTest.testIncludesPassParticipantsInPendingBetsWithYouFirstIfParticipating`
- L48: counts only selected bet participants in the overflow spot → `FeaturesTableSeatingTest.testCountsOnlySelectedBetParticipantsInTheOverflowSpot`
- L55: an open bet with no participants leaves the felt empty → `FeaturesTableSeatingTest.testAnOpenBetWithNoParticipantsLeavesTheFeltEmpty`
- L59: shows everyone when no open bets remain (%s) → `FeaturesTableSeatingTest.testShowsEveryoneWhenNoOpenBetsRemainS` (parameterized)
- L64: switching selected bets replaces seats rather than combining participants → `FeaturesTableSeatingTest.testSwitchingSelectedBetsReplacesSeatsRatherThanCombiningParticipants`
- L71: keeps table order regardless of join order and assigns unique destinations → `FeaturesTableSeatingTest.testKeepsTableOrderRegardlessOfJoinOrderAndAssignsUniqueDestinations`
- L86: hides other players' picks while the bet is active → `FeaturesTableSeatingTest.testHidesOtherPlayersPicksWhileTheBetIsActive`
- L91: reveals picks once the bet is pending → `FeaturesTableSeatingTest.testRevealsPicksOnceTheBetIsPending`
- L96: 4fun winners show Won, not 'Lost 0.00' → `FeaturesTableSeatingTest.test4funWinnersShowWonNotLost000`

## features/table/settlements.test.ts

Source: [features/table/settlements.test.ts](../../client/src/features/table/settlements.test.ts).

- L34: a negative line means the member pays the host → `FeaturesTableSettlementsTest.testANegativeLineMeansTheMemberPaysTheHost`
- L40: only the payer can mark sent; only the receiver can confirm → `FeaturesTableSettlementsTest.testOnlyThePayerCanMarkSentOnlyTheReceiverCanConfirm`
- L48: confirmed payments drop off the outstanding list → `FeaturesTableSettlementsTest.testConfirmedPaymentsDropOffTheOutstandingList`
- L75: adds unsettled resolved results and unpaid settlement payments → `FeaturesTableSettlementsTest.testAddsUnsettledResolvedResultsAndUnpaidSettlementPayments`
- L82: ignores settled bets and confirmed payments → `FeaturesTableSettlementsTest.testIgnoresSettledBetsAndConfirmedPayments`
- L88: a payment marked sent still counts until it's confirmed → `FeaturesTableSettlementsTest.testAPaymentMarkedSentStillCountsUntilItSConfirmed`
- L97: only the person owed can forgive, and only before it's marked sent → `FeaturesTableSettlementsTest.testOnlyThePersonOwedCanForgiveAndOnlyBeforeItSMarkedSent`
- L104: the payer can ask once; after a decline, again only after a day → `FeaturesTableSettlementsTest.testThePayerCanAskOnceAfterADeclineAgainOnlyAfterADay`
- L115: forgiven settle-ups clear from the points number → `FeaturesTableSettlementsTest.testForgivenSettleUpsClearFromThePointsNumber`
- L123: the points number ignores results a host hasn't settled yet → `FeaturesTableSettlementsTest.testThePointsNumberIgnoresResultsAHostHasnTSettledYet`
- L127: a payment marked sent can go back to unpaid if it never arrived → `FeaturesTableSettlementsTest.testAPaymentMarkedSentCanGoBackToUnpaidIfItNeverArrived`

## features/table/unread.test.ts

Source: [features/table/unread.test.ts](../../client/src/features/table/unread.test.ts).

Individual review of all five current cases (server behavior remains unimplemented):

| Frontend case | Backend target |
|---|---|
| start at the first message after last read | Compute the first visible unread message from the persisted table ordering, excluding the caller's own messages. |
| caught up at the end | Return zero unread and a null first-unread ID. |
| own messages are never unread | Exclude the caller's messages while counting and finding the first unread. |
| never opened means everything is unread | With no read position, count all visible non-own messages, including system messages. |
| jump to latest and never move back | Advance only to a visible message in the same table; concurrent updates cannot move the position backwards. Keep the marker meaningful after a previously read message is deleted, rather than adopting the prototype's missing-ID fallback. |

## features/table/use-chat-window.test.tsx

Source: [features/table/use-chat-window.test.tsx](../../client/src/features/table/use-chat-window.test.tsx).

- L44: includes a sent message when jumping and receiving it in the same update → `FeaturesTableUseChatWindowTest.testIncludesASentMessageWhenJumpingAndReceivingItInTheSameUpdate`
- L68: mounts only first-unread context, replaces the gap on jump, and bounds incoming messages → `FeaturesTableUseChatWindowTest.testMountsOnlyFirstUnreadContextReplacesTheGapOnJumpAndBoundsIncomingMessages`

## test/app-routing.test.tsx

Source: [test/app-routing.test.tsx](../../client/src/test/app-routing.test.tsx).

- L10: matches a page for / instead of falling back to not found → `TestAppRoutingTest.testMatchesAPageForInsteadOfFallingBackToNotFound`

