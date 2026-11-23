-- P2Picks schema baseline (squashed 2026-11-23 from V1–V47; data was wiped, no history kept).
-- Tables appear in the order their features arrived, each with its constraints, indexes and triggers.

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

COMMENT ON EXTENSION citext IS 'data type for case-insensitive character strings';

CREATE FUNCTION assign_chat_message_position() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Assign the sequence after locking the table, so concurrent commits cannot reorder its messages.
    PERFORM 1 FROM tables WHERE id = NEW.table_id FOR UPDATE;
    NEW.message_order := nextval('chat_message_order_seq');
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION check_ledger_zero_sum() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (SELECT COALESCE(sum(delta_cents), 0) FROM ledger_entries WHERE bet_id = NEW.bet_id) <> 0 THEN
        RAISE EXCEPTION 'Ledger entries must sum to zero for bet %', NEW.bet_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION lock_ledger_bet() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Serialize writers before checking a bet's deferred zero-sum constraint.
    PERFORM 1 FROM bets WHERE id = NEW.bet_id FOR UPDATE;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_chat_message_position() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF ROW(NEW.id, NEW.table_id, NEW.message_order, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.table_id, OLD.message_order, OLD.created_at) THEN
        RAISE EXCEPTION 'Chat message position is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_settle() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Settles are immutable' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.id, NEW.sender_id, NEW.other_id, NEW.kind, NEW.payer_id, NEW.payee_id, NEW.amount_cents,
           NEW.created_at, NEW.reject_by)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.sender_id, OLD.other_id, OLD.kind, OLD.payer_id, OLD.payee_id, OLD.amount_cents,
           OLD.created_at, OLD.reject_by)
       OR (OLD.rejected_at IS NOT NULL AND NEW.rejected_at IS DISTINCT FROM OLD.rejected_at) THEN
        RAISE EXCEPTION 'Only a settle''s rejection can change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_settlement_line_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF ROW(NEW.id, NEW.settlement_id, NEW.table_id, NEW.host_id, NEW.payer_id,
           NEW.payee_id, NEW.amount_cents, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.settlement_id, OLD.table_id, OLD.host_id, OLD.payer_id,
           OLD.payee_id, OLD.amount_cents, OLD.created_at) THEN
        RAISE EXCEPTION 'Settlement line identity and amount are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION reject_account_role_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.role IS DISTINCT FROM OLD.role THEN
        RAISE EXCEPTION 'Account roles cannot be changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are append-only' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION reject_settlement_history_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Settlement history is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE SEQUENCE chat_message_order_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- users
CREATE TABLE users (
    id uuid NOT NULL,
    username citext,
    hue smallint,
    password_hash text,
    role text NOT NULL,
    status text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT users_identity_check CHECK ((((status = 'DELETED'::text) AND (deleted_at IS NOT NULL) AND (username IS NULL) AND (hue IS NULL) AND (password_hash IS NULL)) OR ((status = ANY (ARRAY['ACTIVE'::text, 'FROZEN'::text])) AND (deleted_at IS NULL) AND (username IS NOT NULL) AND ((username)::text ~ '^[a-z0-9_]{3,15}$'::text) AND (hue IS NOT NULL) AND ((hue >= 1) AND (hue <= 5)) AND (password_hash IS NOT NULL) AND (password_hash <> ''::text)))),
    CONSTRAINT users_role_check CHECK ((role = ANY (ARRAY['PLAYER'::text, 'ADMIN'::text]))),
    CONSTRAINT users_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'FROZEN'::text, 'DELETED'::text]))),
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_username_key UNIQUE (username)
);
CREATE TRIGGER users_role_immutable BEFORE UPDATE OF role ON users FOR EACH ROW EXECUTE FUNCTION reject_account_role_change();

-- auth_sessions
CREATE TABLE auth_sessions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT auth_sessions_expiry_check CHECK ((expires_at > created_at)),
    CONSTRAINT auth_sessions_revocation_check CHECK (((revoked_at IS NULL) OR (revoked_at >= created_at))),
    CONSTRAINT auth_sessions_pkey PRIMARY KEY (id),
    CONSTRAINT auth_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX auth_sessions_user_id_idx ON auth_sessions USING btree (user_id);

-- blocks
CREATE TABLE blocks (
    blocker_id uuid NOT NULL,
    blocked_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT blocks_not_self CHECK ((blocker_id <> blocked_id)),
    CONSTRAINT blocks_pkey PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT blocks_blocked_id_fkey FOREIGN KEY (blocked_id) REFERENCES users(id),
    CONSTRAINT blocks_blocker_id_fkey FOREIGN KEY (blocker_id) REFERENCES users(id)
);
CREATE INDEX blocks_blocked_id_idx ON blocks USING btree (blocked_id);

-- follows
CREATE TABLE follows (
    follower_id uuid NOT NULL,
    followee_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT follows_not_self CHECK ((follower_id <> followee_id)),
    CONSTRAINT follows_pkey PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT follows_followee_id_fkey FOREIGN KEY (followee_id) REFERENCES users(id),
    CONSTRAINT follows_follower_id_fkey FOREIGN KEY (follower_id) REFERENCES users(id)
);
CREATE INDEX follows_followee_id_idx ON follows USING btree (followee_id);

-- tables
CREATE TABLE tables (
    id uuid NOT NULL,
    name text NOT NULL,
    emoji text NOT NULL,
    hosts_only_bets boolean DEFAULT false NOT NULL,
    u2pick_enabled boolean DEFAULT true NOT NULL,
    quickpick_code text NOT NULL,
    quickpicks_enabled boolean DEFAULT false NOT NULL,
    quickpick_sharing boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_at timestamp with time zone,
    polls_enabled boolean DEFAULT true NOT NULL,
    jackblack_access text DEFAULT 'everyone'::text NOT NULL,
    holdem_access text DEFAULT 'everyone'::text NOT NULL,
    CONSTRAINT sharing_requires_quickpicks CHECK (((NOT quickpick_sharing) OR quickpicks_enabled)),
    CONSTRAINT tables_emoji_check CHECK ((btrim(emoji) <> ''::text)),
    CONSTRAINT tables_holdem_access_check CHECK ((holdem_access = ANY (ARRAY['hosts'::text, 'everyone'::text, 'off'::text]))),
    CONSTRAINT tables_jackblack_access_check CHECK ((jackblack_access = ANY (ARRAY['hosts'::text, 'everyone'::text, 'off'::text]))),
    CONSTRAINT tables_name_check CHECK ((btrim(name) <> ''::text)),
    CONSTRAINT tables_name_length CHECK ((char_length(name) <= 14)),
    CONSTRAINT tables_quickpick_code_check CHECK ((quickpick_code ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$'::text)),
    CONSTRAINT tables_pkey PRIMARY KEY (id),
    CONSTRAINT tables_quickpick_code_key UNIQUE (quickpick_code)
);

-- table_invites
CREATE TABLE table_invites (
    id uuid NOT NULL,
    table_id uuid NOT NULL,
    from_id uuid NOT NULL,
    user_id uuid NOT NULL,
    status text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT table_invites_not_self CHECK ((from_id <> user_id)),
    CONSTRAINT table_invites_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'ACCEPTED'::text, 'DECLINED'::text, 'CANCELLED'::text, 'EXPIRED'::text]))),
    CONSTRAINT table_invites_pkey PRIMARY KEY (id),
    CONSTRAINT table_invites_from_id_fkey FOREIGN KEY (from_id) REFERENCES users(id),
    CONSTRAINT table_invites_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id),
    CONSTRAINT table_invites_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE UNIQUE INDEX table_invites_one_pending_per_user ON table_invites USING btree (table_id, user_id) WHERE (status = 'PENDING'::text);
CREATE INDEX table_invites_recipient_status_idx ON table_invites USING btree (user_id, status);

-- table_members
CREATE TABLE table_members (
    table_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role text NOT NULL,
    can_bet boolean DEFAULT true NOT NULL,
    can_text boolean DEFAULT true NOT NULL,
    can_react boolean DEFAULT true NOT NULL,
    can_send_gifs boolean DEFAULT true NOT NULL,
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    can_poll boolean DEFAULT true NOT NULL,
    CONSTRAINT hosts_have_all_permissions CHECK (((role <> 'HOST'::text) OR (can_bet AND can_text AND can_react AND can_send_gifs AND can_poll))),
    CONSTRAINT table_members_role_check CHECK ((role = ANY (ARRAY['HOST'::text, 'MEMBER'::text]))),
    CONSTRAINT table_members_pkey PRIMARY KEY (table_id, user_id),
    CONSTRAINT table_members_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id),
    CONSTRAINT table_members_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX table_members_user_id_idx ON table_members USING btree (user_id);

-- quickpick_subscriptions
CREATE TABLE quickpick_subscriptions (
    subscriber_table_id uuid NOT NULL,
    source_table_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT quickpick_subscriptions_not_self CHECK ((subscriber_table_id <> source_table_id)),
    CONSTRAINT quickpick_subscriptions_pkey PRIMARY KEY (subscriber_table_id, source_table_id),
    CONSTRAINT quickpick_subscriptions_source_table_id_fkey FOREIGN KEY (source_table_id) REFERENCES tables(id),
    CONSTRAINT quickpick_subscriptions_subscriber_table_id_fkey FOREIGN KEY (subscriber_table_id) REFERENCES tables(id)
);
CREATE INDEX quickpick_subscriptions_source_idx ON quickpick_subscriptions USING btree (source_table_id);

-- outbox
CREATE TABLE outbox (
    id uuid NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT outbox_aggregate_id_check CHECK ((btrim(aggregate_id) <> ''::text)),
    CONSTRAINT outbox_aggregate_type_check CHECK ((btrim(aggregate_type) <> ''::text)),
    CONSTRAINT outbox_event_type_check CHECK ((btrim(event_type) <> ''::text)),
    CONSTRAINT outbox_pkey PRIMARY KEY (id)
);
CREATE INDEX outbox_unpublished_idx ON outbox USING btree (created_at, id) WHERE (published_at IS NULL);

-- bets
CREATE TABLE bets (
    id uuid NOT NULL,
    table_id uuid NOT NULL,
    proposer_id uuid NOT NULL,
    mode text NOT NULL,
    game_id text,
    config jsonb NOT NULL,
    question text NOT NULL,
    choices jsonb NOT NULL,
    wager_cents bigint NOT NULL,
    is_4fun boolean NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    locks_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    locked_at timestamp with time zone,
    closed_at timestamp with time zone,
    points_settled_at timestamp with time zone,
    baseline jsonb,
    includes_overtime boolean,
    settler_rule text,
    called_by uuid,
    winning_choice text,
    wash_reason text,
    source_bet_id uuid,
    source_table_id uuid,
    overridden boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    overridden_by uuid,
    overridden_at timestamp with time zone,
    CONSTRAINT bets_baseline_check CHECK (((baseline IS NULL) OR (jsonb_typeof(baseline) = 'object'::text))),
    CONSTRAINT bets_choices_check CHECK (((jsonb_typeof(choices) = 'array'::text) AND (jsonb_array_length(choices) >= 2))),
    CONSTRAINT bets_config_check CHECK ((jsonb_typeof(config) = 'object'::text)),
    CONSTRAINT bets_game_required CHECK (((mode = ANY (ARRAY['u2pick'::text, 'jackblack'::text, 'holdem'::text])) OR (game_id IS NOT NULL))),
    CONSTRAINT bets_locked CHECK ((((status = 'ACTIVE'::text) AND (locked_at IS NULL)) OR ((status <> 'ACTIVE'::text) AND (locked_at IS NOT NULL)))),
    CONSTRAINT bets_mode_check CHECK ((mode = ANY (ARRAY['either_or'::text, 'king_of_the_hill'::text, 'choose_their_fate'::text, 'scorcerer'::text, 'total_disaster'::text, 'give_and_take'::text, 'prop_hunt'::text, 'u2pick'::text, 'jackblack'::text, 'holdem'::text]))),
    CONSTRAINT bets_override_audit CHECK (((overridden_by IS NULL) = (overridden_at IS NULL))),
    CONSTRAINT bets_override_copy CHECK (((NOT overridden) OR ((source_bet_id IS NOT NULL) AND (status = 'WASHED'::text)))),
    CONSTRAINT bets_question_check CHECK ((btrim(question) <> ''::text)),
    CONSTRAINT bets_settler_rule_check CHECK ((settler_rule = ANY (ARRAY['anyone'::text, 'non_participants'::text, 'hosts'::text, 'me'::text]))),
    CONSTRAINT bets_source_is_other_table CHECK (((source_table_id IS NULL) OR (source_table_id <> table_id))),
    CONSTRAINT bets_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'PENDING'::text, 'SETTLED'::text, 'WASHED'::text]))),
    CONSTRAINT bets_unresolved_points CHECK (((points_settled_at IS NULL) OR (status = ANY (ARRAY['SETTLED'::text, 'WASHED'::text])))),
    CONSTRAINT bets_version_check CHECK ((version >= 0)),
    CONSTRAINT bets_wager_cents_check CHECK ((wager_cents >= 0)),
    CONSTRAINT bets_wager_range CHECK ((is_4fun OR ((wager_cents >= 10) AND (wager_cents <= 1000)))),
    CONSTRAINT bets_id_table_unique UNIQUE (id, table_id),
    CONSTRAINT bets_ledger_reference_unique UNIQUE (id, table_id, is_4fun),
    CONSTRAINT bets_pkey PRIMARY KEY (id),
    CONSTRAINT bets_called_by_fkey FOREIGN KEY (called_by) REFERENCES users(id),
    CONSTRAINT bets_overridden_by_fkey FOREIGN KEY (overridden_by) REFERENCES users(id),
    CONSTRAINT bets_proposer_id_fkey FOREIGN KEY (proposer_id) REFERENCES users(id),
    CONSTRAINT bets_source_fk FOREIGN KEY (source_bet_id, source_table_id) REFERENCES bets(id, table_id) MATCH FULL,
    CONSTRAINT bets_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id)
);
CREATE INDEX bets_due_to_lock_idx ON bets USING btree (locks_at, id) WHERE (status = 'ACTIVE'::text);
CREATE UNIQUE INDEX bets_one_copy_per_source_and_table ON bets USING btree (table_id, source_bet_id) WHERE (source_bet_id IS NOT NULL);
CREATE INDEX bets_proposer_idx ON bets USING btree (proposer_id, created_at DESC, id DESC);
CREATE INDEX bets_table_created_idx ON bets USING btree (table_id, created_at DESC);
CREATE INDEX bets_table_status_idx ON bets USING btree (table_id, status);

-- bet_participants
CREATE TABLE bet_participants (
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL,
    participation_order bigint GENERATED ALWAYS AS IDENTITY,
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT bet_participants_participation_order_key UNIQUE (participation_order),
    CONSTRAINT bet_participants_pkey PRIMARY KEY (bet_id, user_id),
    CONSTRAINT bet_participants_bet_id_fkey FOREIGN KEY (bet_id) REFERENCES bets(id),
    CONSTRAINT bet_participants_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

-- picks
CREATE TABLE picks (
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL,
    choice text NOT NULL,
    picked_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT picks_choice_check CHECK (((btrim(choice) <> ''::text) AND (lower(btrim(choice)) <> 'no guess'::text))),
    CONSTRAINT picks_pkey PRIMARY KEY (bet_id, user_id),
    CONSTRAINT picks_bet_id_user_id_fkey FOREIGN KEY (bet_id, user_id) REFERENCES bet_participants(bet_id, user_id)
);
CREATE INDEX picks_user_idx ON picks USING btree (user_id);

-- ledger_entries
CREATE TABLE ledger_entries (
    id uuid NOT NULL,
    table_id uuid NOT NULL,
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL,
    delta_cents bigint NOT NULL,
    is_4fun boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ledger_entries_delta_cents_check CHECK ((delta_cents <> 0)),
    CONSTRAINT ledger_entries_is_4fun_check CHECK ((NOT is_4fun)),
    CONSTRAINT ledger_entries_pkey PRIMARY KEY (id),
    CONSTRAINT ledger_entries_bet_id_table_id_is_4fun_fkey FOREIGN KEY (bet_id, table_id, is_4fun) REFERENCES bets(id, table_id, is_4fun),
    CONSTRAINT ledger_entries_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX ledger_entries_bet_idx ON ledger_entries USING btree (bet_id);
CREATE INDEX ledger_entries_user_table_idx ON ledger_entries USING btree (user_id, table_id);
CREATE TRIGGER ledger_entries_append_only BEFORE DELETE OR UPDATE OR TRUNCATE ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER ledger_entries_lock_bet BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION lock_ledger_bet();
CREATE CONSTRAINT TRIGGER ledger_entries_zero_sum AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_ledger_zero_sum();

-- debt_caps
CREATE TABLE debt_caps (
    user_id uuid NOT NULL,
    cap_cents integer,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT debt_caps_cap_cents_check CHECK ((cap_cents >= 0)),
    CONSTRAINT debt_caps_pkey PRIMARY KEY (user_id),
    CONSTRAINT debt_caps_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

-- settlements
CREATE TABLE settlements (
    id uuid NOT NULL,
    table_id uuid NOT NULL,
    host_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT settlements_id_table_id_host_id_key UNIQUE (id, table_id, host_id),
    CONSTRAINT settlements_id_table_id_key UNIQUE (id, table_id),
    CONSTRAINT settlements_pkey PRIMARY KEY (id),
    CONSTRAINT settlements_host_id_fkey FOREIGN KEY (host_id) REFERENCES users(id),
    CONSTRAINT settlements_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id)
);
CREATE TRIGGER settlements_immutable BEFORE DELETE OR UPDATE OR TRUNCATE ON settlements FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();

-- settlement_bets
CREATE TABLE settlement_bets (
    bet_id uuid NOT NULL,
    table_id uuid NOT NULL,
    settlement_id uuid NOT NULL,
    CONSTRAINT settlement_bets_pkey PRIMARY KEY (bet_id),
    CONSTRAINT settlement_bets_bet_id_table_id_fkey FOREIGN KEY (bet_id, table_id) REFERENCES bets(id, table_id),
    CONSTRAINT settlement_bets_settlement_id_table_id_fkey FOREIGN KEY (settlement_id, table_id) REFERENCES settlements(id, table_id)
);
CREATE INDEX settlement_bets_settlement_idx ON settlement_bets USING btree (settlement_id);
CREATE TRIGGER settlement_bets_immutable BEFORE DELETE OR UPDATE OR TRUNCATE ON settlement_bets FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();

-- settles
CREATE TABLE settles (
    id uuid NOT NULL,
    sender_id uuid NOT NULL,
    other_id uuid NOT NULL,
    kind text NOT NULL,
    payer_id uuid,
    payee_id uuid,
    amount_cents bigint NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    reject_by timestamp with time zone,
    rejected_at timestamp with time zone,
    CONSTRAINT settles_amount_cents_check CHECK ((amount_cents >= 0)),
    CONSTRAINT settles_distinct_parties CHECK ((sender_id <> other_id)),
    CONSTRAINT settles_kind_check CHECK ((kind = ANY (ARRAY['sent'::text, 'received'::text]))),
    CONSTRAINT settles_net_parties CHECK ((((amount_cents = 0) AND (payer_id IS NULL) AND (payee_id IS NULL)) OR ((amount_cents > 0) AND (payer_id IS NOT NULL) AND (payee_id IS NOT NULL) AND (payer_id <> payee_id) AND ((payer_id = sender_id) OR (payer_id = other_id)) AND ((payee_id = sender_id) OR (payee_id = other_id))))),
    CONSTRAINT settles_reject_only_sent CHECK (((rejected_at IS NULL) OR (kind = 'sent'::text))),
    CONSTRAINT settles_window_only_when_sent CHECK (((kind = 'sent'::text) = (reject_by IS NOT NULL))),
    CONSTRAINT settles_pkey PRIMARY KEY (id),
    CONSTRAINT settles_other_id_fkey FOREIGN KEY (other_id) REFERENCES users(id),
    CONSTRAINT settles_payee_id_fkey FOREIGN KEY (payee_id) REFERENCES users(id),
    CONSTRAINT settles_payer_id_fkey FOREIGN KEY (payer_id) REFERENCES users(id),
    CONSTRAINT settles_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES users(id)
);
CREATE INDEX settles_pair_idx ON settles USING btree (LEAST(sender_id, other_id), GREATEST(sender_id, other_id), created_at DESC, id DESC);
CREATE TRIGGER settles_protected BEFORE DELETE OR UPDATE ON settles FOR EACH ROW EXECUTE FUNCTION protect_settle();

-- settlement_lines
CREATE TABLE settlement_lines (
    id uuid NOT NULL,
    settlement_id uuid NOT NULL,
    table_id uuid NOT NULL,
    host_id uuid NOT NULL,
    payer_id uuid NOT NULL,
    payee_id uuid NOT NULL,
    amount_cents bigint NOT NULL,
    status text DEFAULT 'unpaid'::text NOT NULL,
    revision integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    settle_id uuid,
    CONSTRAINT settlement_lines_amount_cents_check CHECK ((amount_cents > 0)),
    CONSTRAINT settlement_lines_closed_by_settle CHECK (((status = 'unpaid'::text) = (settle_id IS NULL))),
    CONSTRAINT settlement_lines_distinct_parties CHECK ((payer_id <> payee_id)),
    CONSTRAINT settlement_lines_include_host CHECK (((payer_id = host_id) OR (payee_id = host_id))),
    CONSTRAINT settlement_lines_revision_check CHECK ((revision >= 0)),
    CONSTRAINT settlement_lines_status_check CHECK ((status = ANY (ARRAY['unpaid'::text, 'paid'::text]))),
    CONSTRAINT settlement_lines_pkey PRIMARY KEY (id),
    CONSTRAINT settlement_lines_payee_id_fkey FOREIGN KEY (payee_id) REFERENCES users(id),
    CONSTRAINT settlement_lines_payer_id_fkey FOREIGN KEY (payer_id) REFERENCES users(id),
    CONSTRAINT settlement_lines_settle_id_fkey FOREIGN KEY (settle_id) REFERENCES settles(id),
    CONSTRAINT settlement_lines_settlement_id_table_id_host_id_fkey FOREIGN KEY (settlement_id, table_id, host_id) REFERENCES settlements(id, table_id, host_id)
);
CREATE UNIQUE INDEX settlement_lines_one_per_counterparty ON settlement_lines USING btree (settlement_id, (
CASE
    WHEN (payer_id = host_id) THEN payee_id
    ELSE payer_id
END));
CREATE INDEX settlement_lines_payee_idx ON settlement_lines USING btree (payee_id, status);
CREATE INDEX settlement_lines_payer_idx ON settlement_lines USING btree (payer_id, status);
CREATE TRIGGER settlement_lines_identity_immutable BEFORE UPDATE ON settlement_lines FOR EACH ROW EXECUTE FUNCTION protect_settlement_line_identity();
CREATE TRIGGER settlement_lines_no_removal BEFORE DELETE OR TRUNCATE ON settlement_lines FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();

-- chat_messages
CREATE TABLE chat_messages (
    id uuid NOT NULL,
    table_id uuid NOT NULL,
    message_order bigint NOT NULL,
    kind text NOT NULL,
    author_id uuid,
    text text,
    gif_id text,
    bet_id uuid,
    settlement_id uuid,
    created_at timestamp with time zone NOT NULL,
    system_event text,
    system_subject_id uuid,
    poll_options text[],
    CONSTRAINT chat_message_shape CHECK ((((kind = 'text'::text) AND (author_id IS NOT NULL) AND (text IS NOT NULL) AND (btrim(text) <> ''::text) AND (gif_id IS NULL) AND (bet_id IS NULL) AND (settlement_id IS NULL) AND (poll_options IS NULL)) OR ((kind = 'gif'::text) AND (author_id IS NOT NULL) AND (gif_id IS NOT NULL) AND (btrim(gif_id) <> ''::text) AND (text IS NULL) AND (bet_id IS NULL) AND (settlement_id IS NULL) AND (poll_options IS NULL)) OR ((kind = 'bet'::text) AND (author_id IS NOT NULL) AND (bet_id IS NOT NULL) AND (text IS NULL) AND (gif_id IS NULL) AND (settlement_id IS NULL) AND (poll_options IS NULL)) OR ((kind = 'system'::text) AND (author_id IS NULL) AND (text IS NOT NULL) AND (btrim(text) <> ''::text) AND (gif_id IS NULL) AND (bet_id IS NULL) AND (settlement_id IS NULL) AND (poll_options IS NULL)) OR ((kind = 'settlement'::text) AND (author_id IS NULL) AND (settlement_id IS NOT NULL) AND (text IS NULL) AND (gif_id IS NULL) AND (bet_id IS NULL) AND (poll_options IS NULL)) OR ((kind = 'poll'::text) AND (author_id IS NOT NULL) AND (text IS NOT NULL) AND (btrim(text) <> ''::text) AND (poll_options IS NOT NULL) AND ((cardinality(poll_options) >= 2) AND (cardinality(poll_options) <= 6)) AND (array_position(poll_options, NULL::text) IS NULL) AND (gif_id IS NULL) AND (bet_id IS NULL) AND (settlement_id IS NULL)))),
    CONSTRAINT chat_messages_kind_check CHECK ((kind = ANY (ARRAY['text'::text, 'gif'::text, 'bet'::text, 'system'::text, 'settlement'::text, 'poll'::text]))),
    CONSTRAINT chat_system_attribution_shape CHECK ((((system_event IS NULL) AND (system_subject_id IS NULL)) OR ((kind = 'system'::text) AND (system_event IS NOT NULL) AND (system_subject_id IS NOT NULL) AND (((system_event = 'host_promoted'::text) AND (text = 'A member is now a host'::text)) OR ((system_event = 'host_demoted'::text) AND (text = 'A member is no longer a host'::text)) OR ((system_event = 'bet_override'::text) AND (text ~~ 'A host %'::text)))))),
    CONSTRAINT chat_messages_message_order_key UNIQUE (message_order),
    CONSTRAINT chat_messages_pkey PRIMARY KEY (id),
    CONSTRAINT chat_messages_author_id_fkey FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT chat_messages_bet_id_table_id_fkey FOREIGN KEY (bet_id, table_id) REFERENCES bets(id, table_id),
    CONSTRAINT chat_messages_settlement_id_table_id_fkey FOREIGN KEY (settlement_id, table_id) REFERENCES settlements(id, table_id),
    CONSTRAINT chat_messages_system_subject_id_fkey FOREIGN KEY (system_subject_id) REFERENCES users(id),
    CONSTRAINT chat_messages_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id)
);
ALTER SEQUENCE chat_message_order_seq OWNED BY chat_messages.message_order;
CREATE INDEX chat_messages_author_idx ON chat_messages USING btree (author_id, created_at DESC, id DESC) WHERE (author_id IS NOT NULL);
CREATE UNIQUE INDEX chat_messages_one_per_settlement ON chat_messages USING btree (settlement_id) WHERE (settlement_id IS NOT NULL);
CREATE INDEX chat_messages_table_order_idx ON chat_messages USING btree (table_id, message_order);
CREATE TRIGGER chat_messages_assign_position BEFORE INSERT ON chat_messages FOR EACH ROW EXECUTE FUNCTION assign_chat_message_position();
CREATE TRIGGER chat_messages_position_immutable BEFORE UPDATE ON chat_messages FOR EACH ROW EXECUTE FUNCTION protect_chat_message_position();

-- chat_read_positions
CREATE TABLE chat_read_positions (
    table_id uuid NOT NULL,
    user_id uuid NOT NULL,
    last_read_order bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chat_read_positions_last_read_order_check CHECK ((last_read_order > 0)),
    CONSTRAINT chat_read_positions_pkey PRIMARY KEY (table_id, user_id),
    CONSTRAINT chat_read_positions_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id),
    CONSTRAINT chat_read_positions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX chat_read_positions_user_idx ON chat_read_positions USING btree (user_id);

-- reactions
CREATE TABLE reactions (
    message_id uuid NOT NULL,
    user_id uuid NOT NULL,
    emoji text NOT NULL,
    CONSTRAINT reactions_emoji_check CHECK ((btrim(emoji) <> ''::text)),
    CONSTRAINT reactions_pkey PRIMARY KEY (message_id, user_id),
    CONSTRAINT reactions_message_id_fkey FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
    CONSTRAINT reactions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX reactions_user_idx ON reactions USING btree (user_id);

-- feedback
CREATE TABLE feedback (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    kind text NOT NULL,
    text text NOT NULL,
    link text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    status text DEFAULT 'NEW'::text NOT NULL,
    admin_note text,
    handled_by uuid,
    handled_at timestamp with time zone,
    CONSTRAINT feedback_admin_note_check CHECK (((admin_note IS NULL) OR (char_length(admin_note) <= 500))),
    CONSTRAINT feedback_kind_check CHECK ((kind = ANY (ARRAY['mode'::text, 'perk'::text, 'gif'::text, 'bug'::text, 'other'::text]))),
    CONSTRAINT feedback_status_check CHECK ((status = ANY (ARRAY['NEW'::text, 'DONE'::text]))),
    CONSTRAINT feedback_text_check CHECK (((char_length(btrim(text)) >= 5) AND (char_length(btrim(text)) <= 1000))),
    CONSTRAINT feedback_link_check CHECK (((link IS NULL) OR ((((lower("left"(link, 7)) = 'http://'::text) AND (char_length(link) > 7)) OR ((lower("left"(link, 8)) = 'https://'::text) AND (char_length(link) > 8))) AND (translate(link, (((((chr(9) || chr(10)) || chr(11)) || chr(12)) || chr(13)) || '                  　﻿'::text), ''::text) = link)))),
    CONSTRAINT feedback_pkey PRIMARY KEY (id),
    CONSTRAINT feedback_handled_by_fkey FOREIGN KEY (handled_by) REFERENCES users(id),
    CONSTRAINT feedback_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX feedback_created_at_idx ON feedback USING btree (created_at DESC, id DESC);
CREATE INDEX feedback_user_idx ON feedback USING btree (user_id);

-- admin_user_flags
CREATE TABLE admin_user_flags (
    user_id uuid NOT NULL,
    flagged boolean DEFAULT false NOT NULL,
    strike_count smallint DEFAULT 0 NOT NULL,
    CONSTRAINT admin_user_flags_strike_count_check CHECK (((strike_count >= 0) AND (strike_count <= 3))),
    CONSTRAINT admin_user_flags_pkey PRIMARY KEY (user_id),
    CONSTRAINT admin_user_flags_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

-- table_archive_members
CREATE TABLE table_archive_members (
    table_id uuid NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT table_archive_members_pkey PRIMARY KEY (table_id, user_id),
    CONSTRAINT table_archive_members_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id),
    CONSTRAINT table_archive_members_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX table_archive_members_user_idx ON table_archive_members USING btree (user_id);

-- http_idempotency
CREATE TABLE http_idempotency (
    user_id uuid NOT NULL,
    key_digest bytea NOT NULL,
    request_digest bytea NOT NULL,
    operation text NOT NULL,
    response_status smallint NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    response_resource_id uuid,
    response_data jsonb,
    CONSTRAINT http_idempotency_key_digest_check CHECK ((octet_length(key_digest) = 32)),
    CONSTRAINT http_idempotency_operation_check CHECK ((btrim(operation) <> ''::text)),
    CONSTRAINT http_idempotency_request_digest_check CHECK ((octet_length(request_digest) = 32)),
    CONSTRAINT http_idempotency_response_data_array CHECK (((response_data IS NULL) OR (jsonb_typeof(response_data) = 'array'::text))),
    CONSTRAINT http_idempotency_response_status_check CHECK ((response_status = ANY (ARRAY[200, 201, 204]))),
    CONSTRAINT http_idempotency_pkey PRIMARY KEY (user_id, key_digest),
    CONSTRAINT http_idempotency_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

-- http_idempotency_key_guard
CREATE TABLE http_idempotency_key_guard (
    singleton boolean DEFAULT true NOT NULL,
    key_verifier bytea NOT NULL,
    CONSTRAINT http_idempotency_key_guard_key_verifier_check CHECK ((octet_length(key_verifier) = 32)),
    CONSTRAINT http_idempotency_key_guard_singleton_check CHECK (singleton),
    CONSTRAINT http_idempotency_key_guard_pkey PRIMARY KEY (singleton)
);

-- invite_preferences
CREATE TABLE invite_preferences (
    user_id uuid NOT NULL,
    friends text DEFAULT 'open'::text NOT NULL,
    following text DEFAULT 'request'::text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT invite_preferences_following_check CHECK ((following = ANY (ARRAY['open'::text, 'request'::text, 'closed'::text]))),
    CONSTRAINT invite_preferences_friends_check CHECK ((friends = ANY (ARRAY['open'::text, 'request'::text, 'closed'::text]))),
    CONSTRAINT invite_preferences_pkey PRIMARY KEY (user_id),
    CONSTRAINT invite_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

-- settlement_pair_reviews
CREATE TABLE settlement_pair_reviews (
    user_id uuid NOT NULL,
    other_user_id uuid NOT NULL,
    revision uuid NOT NULL,
    reviewed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT settlement_pair_reviews_check CHECK ((user_id <> other_user_id)),
    CONSTRAINT settlement_pair_reviews_pkey PRIMARY KEY (user_id, other_user_id),
    CONSTRAINT settlement_pair_reviews_other_user_id_fkey FOREIGN KEY (other_user_id) REFERENCES users(id),
    CONSTRAINT settlement_pair_reviews_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

-- games
CREATE TABLE games (
    id text NOT NULL,
    league text NOT NULL,
    provider_id text NOT NULL,
    status text NOT NULL,
    starts_at timestamp with time zone NOT NULL,
    period integer,
    clock text,
    home_abbreviation text NOT NULL,
    home_name text NOT NULL,
    home_city text NOT NULL,
    home_score integer,
    away_abbreviation text NOT NULL,
    away_name text NOT NULL,
    away_city text NOT NULL,
    away_score integer,
    observed_at timestamp with time zone NOT NULL,
    provider_state text,
    home_periods integer[] DEFAULT '{}'::integer[] NOT NULL,
    away_periods integer[] DEFAULT '{}'::integer[] NOT NULL,
    final_at timestamp with time zone,
    CONSTRAINT games_away_abbreviation_check CHECK ((btrim(away_abbreviation) <> ''::text)),
    CONSTRAINT games_away_score_check CHECK (((away_score IS NULL) OR (away_score >= 0))),
    CONSTRAINT games_final_at CHECK (((final_at IS NULL) OR (status = 'FINAL'::text))),
    CONSTRAINT games_home_abbreviation_check CHECK ((btrim(home_abbreviation) <> ''::text)),
    CONSTRAINT games_home_score_check CHECK (((home_score IS NULL) OR (home_score >= 0))),
    CONSTRAINT games_id_check CHECK ((id ~ '^(NBA|NFL)-[A-Za-z0-9_-]+$'::text)),
    CONSTRAINT games_league_check CHECK ((league = ANY (ARRAY['NBA'::text, 'NFL'::text]))),
    CONSTRAINT games_period_check CHECK (((period IS NULL) OR (period >= 1))),
    CONSTRAINT games_provider_id_check CHECK ((btrim(provider_id) <> ''::text)),
    CONSTRAINT games_status_check CHECK ((status = ANY (ARRAY['SCHEDULED'::text, 'LIVE'::text, 'FINAL'::text, 'POSTPONED'::text]))),
    CONSTRAINT games_league_provider_id_key UNIQUE (league, provider_id),
    CONSTRAINT games_pkey PRIMARY KEY (id)
);
CREATE INDEX games_live_idx ON games USING btree (starts_at) WHERE (status = 'LIVE'::text);
CREATE INDEX games_starts_at_idx ON games USING btree (starts_at);

-- sports_feed_status
CREATE TABLE sports_feed_status (
    league text NOT NULL,
    last_success_at timestamp with time zone,
    last_failure_at timestamp with time zone,
    last_failure text,
    players_refreshed_at timestamp with time zone,
    CONSTRAINT sports_feed_status_league_check CHECK ((league = ANY (ARRAY['NBA'::text, 'NFL'::text]))),
    CONSTRAINT sports_feed_status_pkey PRIMARY KEY (league)
);

-- reports
CREATE TABLE reports (
    id uuid NOT NULL,
    reporter_id uuid NOT NULL,
    target_id uuid NOT NULL,
    reason text NOT NULL,
    note text NOT NULL,
    table_id uuid,
    message_id uuid,
    message_snapshot text,
    status text DEFAULT 'OPEN'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT reports_check CHECK (((char_length(note) <= 500) AND ((reason <> 'other'::text) OR (char_length(btrim(note)) > 0)))),
    CONSTRAINT reports_message_needs_table CHECK (((message_id IS NULL) OR (table_id IS NOT NULL))),
    CONSTRAINT reports_not_self CHECK ((reporter_id <> target_id)),
    CONSTRAINT reports_reason_check CHECK ((reason = ANY (ARRAY['harassment'::text, 'settlement'::text, 'spam'::text, 'offensive'::text, 'other'::text]))),
    CONSTRAINT reports_status_check CHECK ((status = ANY (ARRAY['OPEN'::text, 'RESOLVED'::text, 'DISMISSED'::text]))),
    CONSTRAINT reports_pkey PRIMARY KEY (id),
    CONSTRAINT reports_message_id_fkey FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE SET NULL,
    CONSTRAINT reports_reporter_id_fkey FOREIGN KEY (reporter_id) REFERENCES users(id),
    CONSTRAINT reports_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id),
    CONSTRAINT reports_target_id_fkey FOREIGN KEY (target_id) REFERENCES users(id)
);
CREATE INDEX reports_created_idx ON reports USING btree (created_at DESC, id DESC);
CREATE UNIQUE INDEX reports_one_open_per_pair ON reports USING btree (reporter_id, target_id) WHERE (status = 'OPEN'::text);
CREATE INDEX reports_open_created_idx ON reports USING btree (created_at DESC) WHERE (status = 'OPEN'::text);
CREATE INDEX reports_reporter_created_idx ON reports USING btree (reporter_id, created_at DESC);

-- tomato_throws
CREATE TABLE tomato_throws (
    id uuid NOT NULL,
    table_id uuid NOT NULL,
    thrower_id uuid NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tomato_throws_not_self CHECK ((thrower_id <> target_id)),
    CONSTRAINT tomato_throws_pkey PRIMARY KEY (id),
    CONSTRAINT tomato_throws_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id),
    CONSTRAINT tomato_throws_target_id_fkey FOREIGN KEY (target_id) REFERENCES users(id),
    CONSTRAINT tomato_throws_thrower_id_fkey FOREIGN KEY (thrower_id) REFERENCES users(id)
);
CREATE INDEX tomato_throws_table_created_idx ON tomato_throws USING btree (table_id, created_at DESC);
CREATE INDEX tomato_throws_thrower_idx ON tomato_throws USING btree (thrower_id, table_id, created_at DESC);

-- game_snapshots
CREATE TABLE game_snapshots (
    id bigint GENERATED ALWAYS AS IDENTITY,
    game_id text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    status text NOT NULL,
    provider_state text,
    period integer,
    clock text,
    home_score integer,
    away_score integer,
    home_periods integer[] NOT NULL,
    away_periods integer[] NOT NULL,
    CONSTRAINT game_snapshots_away_score_check CHECK (((away_score IS NULL) OR (away_score >= 0))),
    CONSTRAINT game_snapshots_home_score_check CHECK (((home_score IS NULL) OR (home_score >= 0))),
    CONSTRAINT game_snapshots_period_check CHECK (((period IS NULL) OR (period >= 1))),
    CONSTRAINT game_snapshots_status_check CHECK ((status = ANY (ARRAY['SCHEDULED'::text, 'LIVE'::text, 'FINAL'::text, 'POSTPONED'::text]))),
    CONSTRAINT game_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT game_snapshots_game_id_fkey FOREIGN KEY (game_id) REFERENCES games(id)
);
CREATE INDEX game_snapshots_game_idx ON game_snapshots USING btree (game_id, observed_at, id);

-- players
CREATE TABLE players (
    id text NOT NULL,
    league text NOT NULL,
    provider_id text NOT NULL,
    first_name text NOT NULL,
    last_name text NOT NULL,
    "position" text,
    team_abbreviation text,
    active boolean DEFAULT true NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT players_id_check CHECK ((id ~ '^(NBA|NFL)-[A-Za-z0-9_-]+$'::text)),
    CONSTRAINT players_league_check CHECK ((league = ANY (ARRAY['NBA'::text, 'NFL'::text]))),
    CONSTRAINT players_provider_id_check CHECK ((btrim(provider_id) <> ''::text)),
    CONSTRAINT players_league_provider_id_key UNIQUE (league, provider_id),
    CONSTRAINT players_pkey PRIMARY KEY (id)
);
CREATE INDEX players_team_idx ON players USING btree (league, team_abbreviation) WHERE active;

-- player_game_stats
CREATE TABLE player_game_stats (
    game_id text NOT NULL,
    player_id text NOT NULL,
    team_abbreviation text,
    played boolean NOT NULL,
    stats jsonb NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT player_game_stats_pkey PRIMARY KEY (game_id, player_id),
    CONSTRAINT player_game_stats_game_id_fkey FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT player_game_stats_player_id_fkey FOREIGN KEY (player_id) REFERENCES players(id)
);

-- player_stat_snapshots
CREATE TABLE player_stat_snapshots (
    id bigint GENERATED ALWAYS AS IDENTITY,
    game_id text NOT NULL,
    player_id text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    played boolean NOT NULL,
    stats jsonb NOT NULL,
    CONSTRAINT player_stat_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT player_stat_snapshots_game_id_fkey FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT player_stat_snapshots_player_id_fkey FOREIGN KEY (player_id) REFERENCES players(id)
);
CREATE INDEX player_stat_snapshots_idx ON player_stat_snapshots USING btree (game_id, player_id, observed_at, id);

-- poll_votes
CREATE TABLE poll_votes (
    message_id uuid NOT NULL,
    user_id uuid NOT NULL,
    option_index smallint NOT NULL,
    voted_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT poll_votes_option_index_check CHECK (((option_index >= 0) AND (option_index <= 5))),
    CONSTRAINT poll_votes_pkey PRIMARY KEY (message_id, user_id),
    CONSTRAINT poll_votes_message_id_fkey FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
    CONSTRAINT poll_votes_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX poll_votes_user_idx ON poll_votes USING btree (user_id);

-- settle_lines
CREATE TABLE settle_lines (
    settle_id uuid NOT NULL,
    line_id uuid NOT NULL,
    CONSTRAINT settle_lines_pkey PRIMARY KEY (settle_id, line_id),
    CONSTRAINT settle_lines_line_id_fkey FOREIGN KEY (line_id) REFERENCES settlement_lines(id),
    CONSTRAINT settle_lines_settle_id_fkey FOREIGN KEY (settle_id) REFERENCES settles(id)
);
CREATE INDEX settle_lines_line_idx ON settle_lines USING btree (line_id);
CREATE TRIGGER settle_lines_immutable BEFORE DELETE OR UPDATE OR TRUNCATE ON settle_lines FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();

-- admin_audit_log
CREATE TABLE admin_audit_log (
    id uuid NOT NULL,
    admin_id uuid NOT NULL,
    action text NOT NULL,
    subject_id uuid NOT NULL,
    query text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT admin_audit_log_action_check CHECK ((action = ANY (ARRAY['view_user'::text, 'search_activity'::text]))),
    CONSTRAINT admin_audit_log_query_check CHECK (((query IS NULL) OR (char_length(query) <= 200))),
    CONSTRAINT admin_audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT admin_audit_log_admin_id_fkey FOREIGN KEY (admin_id) REFERENCES users(id),
    CONSTRAINT admin_audit_log_subject_id_fkey FOREIGN KEY (subject_id) REFERENCES users(id)
);
CREATE INDEX admin_audit_log_created_idx ON admin_audit_log USING btree (created_at DESC, id DESC);
CREATE INDEX admin_audit_log_subject_idx ON admin_audit_log USING btree (subject_id, created_at DESC);

-- perf_minutes
CREATE TABLE perf_minutes (
    minute timestamp with time zone NOT NULL,
    source text NOT NULL,
    method text NOT NULL,
    route text NOT NULL,
    requests integer NOT NULL,
    server_errors integer NOT NULL,
    client_errors integer NOT NULL,
    rate_limited integer NOT NULL,
    total_ms bigint NOT NULL,
    max_ms integer NOT NULL,
    buckets integer[] NOT NULL,
    CONSTRAINT perf_minutes_check CHECK (((server_errors >= 0) AND (server_errors <= requests))),
    CONSTRAINT perf_minutes_check1 CHECK (((client_errors >= 0) AND (client_errors <= requests))),
    CONSTRAINT perf_minutes_check2 CHECK (((rate_limited >= 0) AND (rate_limited <= requests))),
    CONSTRAINT perf_minutes_max_ms_check CHECK ((max_ms >= 0)),
    CONSTRAINT perf_minutes_method_check CHECK (((char_length(method) >= 1) AND (char_length(method) <= 10))),
    CONSTRAINT perf_minutes_minute_check CHECK ((date_trunc('minute'::text, minute) = minute)),
    CONSTRAINT perf_minutes_requests_check CHECK ((requests > 0)),
    CONSTRAINT perf_minutes_route_check CHECK (((char_length(route) >= 1) AND (char_length(route) <= 200))),
    CONSTRAINT perf_minutes_source_check CHECK ((source = ANY (ARRAY['server'::text, 'client'::text]))),
    CONSTRAINT perf_minutes_total_ms_check CHECK ((total_ms >= 0)),
    CONSTRAINT perf_minutes_pkey PRIMARY KEY (minute, source, method, route)
);

-- sign_in_codes
CREATE TABLE sign_in_codes (
    user_id uuid NOT NULL,
    lookup bytea NOT NULL,
    sealed bytea NOT NULL,
    rotated_at timestamp with time zone DEFAULT now() NOT NULL,
    rotated_by uuid,
    CONSTRAINT sign_in_codes_lookup_check CHECK ((octet_length(lookup) = 32)),
    CONSTRAINT sign_in_codes_sealed_check CHECK ((octet_length(sealed) > 12)),
    CONSTRAINT sign_in_codes_lookup_key UNIQUE (lookup),
    CONSTRAINT sign_in_codes_pkey PRIMARY KEY (user_id),
    CONSTRAINT sign_in_codes_rotated_by_fkey FOREIGN KEY (rotated_by) REFERENCES users(id),
    CONSTRAINT sign_in_codes_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

-- lan_party
CREATE TABLE lan_party (
    singleton boolean DEFAULT true NOT NULL,
    table_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lan_party_singleton_check CHECK (singleton),
    CONSTRAINT lan_party_pkey PRIMARY KEY (singleton),
    CONSTRAINT lan_party_table_id_fkey FOREIGN KEY (table_id) REFERENCES tables(id)
);

-- card_games
CREATE TABLE card_games (
    bet_id uuid NOT NULL,
    state jsonb NOT NULL,
    next_deadline timestamp with time zone,
    CONSTRAINT card_games_state_check CHECK ((jsonb_typeof(state) = 'object'::text)),
    CONSTRAINT card_games_pkey PRIMARY KEY (bet_id),
    CONSTRAINT card_games_bet_id_fkey FOREIGN KEY (bet_id) REFERENCES bets(id)
);
CREATE INDEX card_games_due_idx ON card_games USING btree (next_deadline) WHERE (next_deadline IS NOT NULL);

-- card_players
CREATE TABLE card_players (
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL,
    risk_cents bigint NOT NULL,
    joined_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT card_players_risk_cents_check CHECK ((risk_cents >= 0)),
    CONSTRAINT card_players_pkey PRIMARY KEY (bet_id, user_id),
    CONSTRAINT card_players_bet_id_user_id_fkey FOREIGN KEY (bet_id, user_id) REFERENCES bet_participants(bet_id, user_id)
);

-- gif_favorites
CREATE TABLE gif_favorites (
    user_id uuid NOT NULL,
    gif_id text NOT NULL,
    starred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT gif_favorites_gif_id_check CHECK ((gif_id ~ '^[a-z0-9_-]{1,40}$'::text)),
    CONSTRAINT gif_favorites_pkey PRIMARY KEY (user_id, gif_id),
    CONSTRAINT gif_favorites_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
