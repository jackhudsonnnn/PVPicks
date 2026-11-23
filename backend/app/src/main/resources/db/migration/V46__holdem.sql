-- Hold'em (docs: domain rules, "Hold'em"): Texas hold'em, one full cycle of hands around the table. Like Jackblack
-- it's a card game played on its ticket, so the two now share their tables: a game's hidden state (`card_games`)
-- and each player's worst case held against their points limit (`card_players`). Balances and the points limit sum
-- `card_players` for every card game.
ALTER TABLE jackblack_games RENAME TO card_games;
ALTER TABLE card_games RENAME CONSTRAINT jackblack_games_pkey TO card_games_pkey;
ALTER INDEX jackblack_games_due_idx RENAME TO card_games_due_idx;
ALTER TABLE jackblack_players RENAME TO card_players;
ALTER TABLE card_players RENAME CONSTRAINT jackblack_players_pkey TO card_players_pkey;

-- Who may propose Hold'em at a table, set by its hosts.
ALTER TABLE tables ADD COLUMN holdem_access text NOT NULL DEFAULT 'everyone'
    CHECK (holdem_access IN ('hosts', 'everyone', 'off'));

ALTER TABLE bets DROP CONSTRAINT bets_mode_check;
ALTER TABLE bets ADD CONSTRAINT bets_mode_check CHECK (mode IN (
    'either_or', 'king_of_the_hill', 'choose_their_fate', 'scorcerer',
    'total_disaster', 'give_and_take', 'prop_hunt', 'u2pick', 'jackblack', 'holdem'
));
ALTER TABLE bets DROP CONSTRAINT bets_game_required;
ALTER TABLE bets ADD CONSTRAINT bets_game_required CHECK (mode IN ('u2pick', 'jackblack', 'holdem') OR game_id IS NOT NULL);
