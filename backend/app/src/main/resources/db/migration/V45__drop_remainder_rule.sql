-- Leftover cents have one rule now (docs: domain rules, "Payouts"): winners split the losers' pot evenly and the
-- cents that don't divide go one each to winners in a random order. The table setting and the rule recorded on
-- each bet at lock are gone; a bet only records when it locked.
ALTER TABLE bets DROP CONSTRAINT bets_locked_remainder;
ALTER TABLE bets DROP COLUMN remainder_rule;
ALTER TABLE bets DROP COLUMN remainder_host_id;
ALTER TABLE bets ADD CONSTRAINT bets_locked CHECK (
    (status = 'ACTIVE' AND locked_at IS NULL) OR (status <> 'ACTIVE' AND locked_at IS NOT NULL)
);
ALTER TABLE tables DROP COLUMN remainder_rule;
