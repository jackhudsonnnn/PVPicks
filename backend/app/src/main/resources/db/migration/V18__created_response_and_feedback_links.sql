ALTER TABLE http_idempotency DROP CONSTRAINT http_idempotency_response_status_check;
ALTER TABLE http_idempotency ADD CONSTRAINT http_idempotency_response_status_check
    CHECK (response_status IN (201, 204));

ALTER TABLE feedback DROP CONSTRAINT feedback_link_check;
ALTER TABLE feedback ADD CONSTRAINT feedback_link_check CHECK (
    link IS NULL OR (
        ((lower(left(link, 7)) = 'http://' AND char_length(link) > 7)
         OR (lower(left(link, 8)) = 'https://' AND char_length(link) > 8))
        AND translate(link,
            chr(9) || chr(10) || chr(11) || chr(12) || chr(13)
            || U&'\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000\FEFF',
            '') = link
    )
) NOT VALID;
