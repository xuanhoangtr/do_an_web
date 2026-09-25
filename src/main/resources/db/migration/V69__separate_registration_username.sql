ALTER TABLE users ADD COLUMN username varchar(64);
ALTER TABLE users ADD CONSTRAINT users_username_key UNIQUE (username);
ALTER TABLE users ADD CONSTRAINT users_username_format CHECK (
    username IS NULL OR (username = lower(username) AND username ~ '^[a-z0-9._-]+$')
);
