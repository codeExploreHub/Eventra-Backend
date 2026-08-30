ALTER TABLE users ADD COLUMN IF NOT EXISTS username_normalized VARCHAR(50);

-- Keep this boundary rule aligned with User.trimUsername(): Java String.trim()
-- removes every leading and trailing UTF-16 code unit from U+0000 through U+0020.
UPDATE users
SET username = REGEXP_REPLACE(
    REGEXP_REPLACE(username, '^[\u0000-\u0020]+', ''),
    '[\u0000-\u0020]+$',
    ''
);

-- A named failure identifies invalid historical rows without rewriting them.
-- The anchored REGEXP_REPLACE predicate is shared by H2 and PostgreSQL and
-- is deterministic because the accepted alphabet is explicitly ASCII-only.
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_username_ascii;
ALTER TABLE users ADD CONSTRAINT ck_users_username_ascii
    CHECK (REGEXP_REPLACE(
        username,
        '^[ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_]{3,50}$',
        ''
    ) = '');

-- Explicit ASCII translation matches Java Locale.ROOT for every accepted
-- username even when the database column uses a locale such as Turkish.
UPDATE users
SET username_normalized = TRANSLATE(
    username,
    'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
    'abcdefghijklmnopqrstuvwxyz'
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username_normalized
    ON users (username_normalized);

ALTER TABLE users ALTER COLUMN username_normalized SET NOT NULL;

ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_username_normalized_consistent;
ALTER TABLE users ADD CONSTRAINT ck_users_username_normalized_consistent
    CHECK (username_normalized = TRANSLATE(
        username,
        'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
        'abcdefghijklmnopqrstuvwxyz'
    ));
