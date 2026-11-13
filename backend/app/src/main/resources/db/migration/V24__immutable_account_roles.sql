CREATE FUNCTION reject_account_role_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role IS DISTINCT FROM OLD.role THEN
        RAISE EXCEPTION 'Account roles cannot be changed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_role_immutable
    BEFORE UPDATE OF role ON users
    FOR EACH ROW EXECUTE FUNCTION reject_account_role_change();
