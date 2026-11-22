-- The LAN watch party's table (lan profile only; docs: LAN watch party). One row once the first player is created:
-- every later player is seated at that table. The row stays after the table is deleted (archived), so the party
-- table is never recreated.
CREATE TABLE lan_party (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    table_id uuid NOT NULL REFERENCES tables(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
