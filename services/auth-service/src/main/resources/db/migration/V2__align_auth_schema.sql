-- Reconciles the auth schema with the JPA entities (spring.jpa.hibernate.ddl-auto=validate).
-- V1 is left untouched so its Flyway checksum stays valid on databases that already ran it.

-- Users may sign in purely through an OAuth provider, in which case there is no local password.
ALTER TABLE auth.users ALTER COLUMN password_hash DROP NOT NULL;

-- Comma separated authorities, e.g. "USER" or "USER,ADMIN".
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS roles VARCHAR(255) NOT NULL DEFAULT 'USER';

-- Refresh token rotation: every token belongs to a family, and a rotated token points at
-- its replacement. Reusing an already-rotated token revokes the whole family.
ALTER TABLE auth.refresh_tokens ADD COLUMN IF NOT EXISTS family_id UUID;
ALTER TABLE auth.refresh_tokens ADD COLUMN IF NOT EXISTS replaced_by UUID;

UPDATE auth.refresh_tokens SET family_id = id WHERE family_id IS NULL;
ALTER TABLE auth.refresh_tokens ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family_id ON auth.refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON auth.refresh_tokens (user_id);
