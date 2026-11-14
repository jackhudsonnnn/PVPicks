BEGIN;
SELECT pg_advisory_xact_lock(798032094116);

CREATE SCHEMA IF NOT EXISTS p2picks_dev_seed;
CREATE TABLE IF NOT EXISTS p2picks_dev_seed.runs (
    name text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE
    intermediate_id uuid;
    pro_id uuid;
    intermediate_table_id uuid := gen_random_uuid();
    pro_table_id uuid := gen_random_uuid();
    first_bet_id uuid := gen_random_uuid();
    second_bet_id uuid := gen_random_uuid();
BEGIN
    IF EXISTS (SELECT 1 FROM p2picks_dev_seed.runs WHERE name = 'sample-v1') THEN
        RAISE NOTICE 'Dev samples were already seeded; existing data is unchanged.';
        RETURN;
    END IF;

    IF (SELECT count(*) FROM users
        WHERE (username = 'newbie'::citext AND role = 'PLAYER'
            OR username = 'intermediate'::citext AND role = 'PLAYER'
            OR username = 'pro'::citext AND role = 'PLAYER'
            OR username = 'admin'::citext AND role = 'ADMIN')
          AND status = 'ACTIVE') <> 4 THEN
        RAISE EXCEPTION 'Provision the four active dev accounts before seeding';
    END IF;

    SELECT id INTO STRICT intermediate_id FROM users WHERE username = 'intermediate'::citext;
    SELECT id INTO STRICT pro_id FROM users WHERE username = 'pro'::citext;

    INSERT INTO tables (id, name, emoji, quickpick_code)
    VALUES (intermediate_table_id, 'Watch Party', 'T', 'DEMA2A'),
           (pro_table_id, 'Game Night', 'T', 'DEMB2B');
    INSERT INTO table_members (table_id, user_id, role)
    VALUES (intermediate_table_id, intermediate_id, 'HOST'),
           (intermediate_table_id, pro_id, 'MEMBER'),
           (pro_table_id, pro_id, 'HOST'),
           (pro_table_id, intermediate_id, 'MEMBER');

    INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                      wager_cents, is_4fun, locks_at, settler_rule)
    VALUES (first_bet_id, intermediate_table_id, intermediate_id, 'u2pick',
            '{"question":"Will we host a watch party?","options":["Yes","No"]}'::jsonb,
            'Will we host a watch party?', '["Yes","No"]'::jsonb,
            100, false, now() + interval '30 days', 'hosts'),
           (second_bet_id, pro_table_id, pro_id, 'u2pick',
            '{"question":"What will the next play be?","options":["Pass","Run"]}'::jsonb,
            'What will the next play be?', '["Pass","Run"]'::jsonb,
            0, true, now() + interval '30 days', 'hosts');
    INSERT INTO bet_participants (bet_id, user_id)
    VALUES (first_bet_id, intermediate_id), (first_bet_id, pro_id), (second_bet_id, intermediate_id);
    INSERT INTO picks (bet_id, user_id, choice)
    VALUES (first_bet_id, pro_id, 'Yes'), (second_bet_id, intermediate_id, 'Pass');

    INSERT INTO p2picks_dev_seed.runs (name) VALUES ('sample-v1');
END;
$$;

-- Follows, so members can be added (a host may only add people who follow them):
-- intermediate and pro are friends; newbie follows pro, who has not followed back.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM p2picks_dev_seed.runs WHERE name = 'follows-v1') THEN
        RAISE NOTICE 'Dev follows were already seeded; existing data is unchanged.';
        RETURN;
    END IF;
    INSERT INTO follows (follower_id, followee_id)
    SELECT follower.id, followee.id
    FROM (VALUES ('intermediate', 'pro'), ('pro', 'intermediate'), ('newbie', 'pro')) AS pair (follower, followee)
    JOIN users follower ON follower.username = pair.follower::citext AND follower.status = 'ACTIVE'
    JOIN users followee ON followee.username = pair.followee::citext AND followee.status = 'ACTIVE'
    ON CONFLICT DO NOTHING;
    INSERT INTO p2picks_dev_seed.runs (name) VALUES ('follows-v1');
END;
$$;
COMMIT;
