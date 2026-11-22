-- Table names fit a table card: at most 14 characters (code points) after trimming, matching the API.
-- Existing longer names are shortened rather than rejected; the first 14 characters of a trimmed name are never
-- blank.
UPDATE tables SET name = btrim(left(name, 14)) WHERE char_length(name) > 14;

ALTER TABLE tables ADD CONSTRAINT tables_name_length CHECK (char_length(name) <= 14);
