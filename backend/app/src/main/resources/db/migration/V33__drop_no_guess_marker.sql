-- "__no_guess__" was the mock prototype's internal marker for No guess. The server stores No guess as a
-- participant without a pick, so only the visible words "No guess" stay reserved as a choice.
ALTER TABLE picks DROP CONSTRAINT picks_choice_check;
ALTER TABLE picks ADD CONSTRAINT picks_choice_check
    CHECK (btrim(choice) <> '' AND lower(btrim(choice)) <> 'no guess');
