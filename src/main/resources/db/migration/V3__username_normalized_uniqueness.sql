ALTER TABLE users ADD COLUMN IF NOT EXISTS username_normalized VARCHAR(50);

UPDATE users
SET username_normalized = LOWER(TRIM(username));

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username_normalized
    ON users (username_normalized);

ALTER TABLE users ALTER COLUMN username_normalized SET NOT NULL;
