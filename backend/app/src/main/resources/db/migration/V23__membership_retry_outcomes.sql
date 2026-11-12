ALTER TABLE http_idempotency DROP CONSTRAINT http_idempotency_response_status_check;
ALTER TABLE http_idempotency ADD CONSTRAINT http_idempotency_response_status_check
    CHECK (response_status IN (200, 201, 204));

ALTER TABLE http_idempotency ADD COLUMN response_data jsonb;
ALTER TABLE http_idempotency ADD CONSTRAINT http_idempotency_response_data_array
    CHECK (response_data IS NULL OR jsonb_typeof(response_data) = 'array');
