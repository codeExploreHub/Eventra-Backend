ALTER TABLE users ADD COLUMN IF NOT EXISTS username_normalized VARCHAR(50);

-- Keep this boundary rule aligned with User.trimUsername(): Java String.trim()
-- removes every leading and trailing UTF-16 code unit from U+0000 through U+0020.
UPDATE users
SET username_normalized = LOWER(
    REGEXP_REPLACE(
        REGEXP_REPLACE(username, '^[\u0000-\u0020]+', ''),
        '[\u0000-\u0020]+$',
        ''
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username_normalized
    ON users (username_normalized);

ALTER TABLE users ALTER COLUMN username_normalized SET NOT NULL;
