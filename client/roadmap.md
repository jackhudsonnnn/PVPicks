# Roadmap

- [x] Replace grouped settle-up actions with a dated Sent/Received receipt popup; verify row permissions, history, and phone/desktop layout.

- [x] Move balance-change notices to a warning beside table details; require opening it before sending or asking for forgiveness; verify.

- [x] Move the mobile Preferences dropdown beside the title; preserve desktop tabs and verify.

- [x] Add the optional Debt Cap, default off; lock all changes while any debt exists; enforce wager/lock safeguards and verify.

- [x] Smooth the persistent game ticker with a repeating buffer and isolated game updates; fit table screens below the shared bars; verify mobile and desktop.

- [x] Make mobile Home tables and invites horizontal cards; preserve desktop lists and verify browsing.

- [x] Reset pending payment/forgiveness claims when person balances change; add private review notices and stale-action protection; verify.

- [x] Group Friends settle-ups by person, provide read-only multiple-table breakdowns, and verify full-net actions.

- [x] Replace Home and My Tickets Show more controls with automatic paging inside their scroll areas.
- [x] Bound chat on mobile and desktop around first unread; page both ways, preserve scroll position, and jump to latest without rendering the gap; verify large histories.

- [x] Fix seat collisions; show only selected open bet participants (including Pass), or everyone when no bets are open; retain participant-only overflow and verify on phone/desktop.

- [x] Poker-style Tables: 8-seat cap + rail, searchable members popup, profiles, bet stack, phone Table/Chat tabs
- [x] Friends page: put "Your friends" and "Find people" lists in scrollable containers
- [x] Chat: send GIFs and react to messages with emojis
- [x] Table hosts: "Host" tab on the table screen for hosts; Home tables list highlights tables you host in green
- [x] Home: Live tickets in a horizontal scrollable row
- [x] Reactions: one emoji per user per message
- [x] Host "Settle points": reset table nets to 0, post payouts via host in chat
- [x] Home: remove the Balance/Net/Record stats section
- [x] Table: smooth animations for card swapping and players joining/leaving seats
- [x] Host "leftover cents" setting (host / proposer / earliest pick / random) and About tab for non-hosts
- [x] Track settlement payments: "I sent it" / "I got paid" on settlement cards, Settle-ups list on Friends
- [x] Host switch: "Only hosts can start bets" (members can still join/pick)
- [x] Notification inbox: bell in the nav bar with unread badge, past notifications, paging + saved history
- [x] U2Pick custom mode: proposer writes question + options and picks who settles (anyone / non-participants / hosts / me); host switch to turn it off
- [x] NBA games (sample data) with basketball versions of the 7 modes
- [x] Preferences: Account (name, username, avatar color) and Display (text size, animations, pick stats, 24-hour clock) tabs
- [x] Follows: following each other = friends; Friends page tabs, follow back
- [x] Preferences "Tables" tab: who can add you (friends / people you follow: open, request, closed)
- [x] Yellow invite items in Your tables with "Accept?" popup
- [x] Host "Add players": add all friends / add all followers; tables can be created empty
- [x] Bug: seat profile picture covers the name on the table
- [x] Bug: ticket swap animation glitches at the end
- [x] Tickets: picking a choice joins the bet (no Join button); clearing keeps you in
- [x] Home: Your tables list about 25% shorter
- [x] Friends: Friends/Following/Followers/Find people in one 4-option toggle (2x2 on small screens)
- [x] All toggles/tab strips use the shared SegmentedTabs with a sliding highlight (restyle odd ones to match)
- [x] My Tickets: vertical scroll list + 'All' filter
- [x] Guide: payout card inside Bet lifecycle; "How we make money" section with Patreon popup
- [x] Vertical scroll lists hand off to the page at their top/bottom
- [x] Peers tab: win-all / all-wash / lose-all views (calculated, one stored value)
- [x] 4fun bets (no points, nothing tracked)
- [x] Table header cleanup on phones; members summary at top of About/Host
- [x] Leftover cents audit + tests
- [x] Mode audit + per-mode Advanced options (resolve at, overtime)
- [x] Ticket links back to table (incl. Details popup)
- [x] Confirm pick popup (first pick + changes)
- [x] Sign-in page + demo accounts newbie/intermediate/pro
- [x] Chat opens at first unread, New messages line, Jump to latest
- [x] Home live games ticker replaces Games section
- [x] Scroll lists: smooth slide + fade when items are added or removed
- [x] Delete account (Preferences > Account), "Deleted user" ledger, warnings on sign-in/tables + Guide
- [x] Report & block (profile, chat, Friends), Safety tab, Guide section on how reports are handled
- [x] Report reason "Won't pay": remind that settlement disputes are handled privately, link to Guide
- [x] Admin demo login (admin/admin123) with live dashboards (donut, line, bar charts) + reports queue
- [x] Confirm popup before unfollowing someone
- [x] Friends: New followers always shown, horizontal transparent row, empty text "Nobody wants to be your friend right now."
- [x] Quick Picks: share code, subscribe, auto-post copies, U2Pick follows source + host override, unsubscribe/new code, source label, Guide
- [x] Backend handoff doc v2: balldontlie game feed in dev and prod too (scripted feed only for tests)
- [x] Code cleanup pass for the backend move: unused code removed, shared pieces, data access decoupled, bugs fixed, files tidied
