CREATE INDEX IF NOT EXISTS idx_ship_infos_user_default_created
    ON ship_infos (user_id, is_default DESC, created_at DESC);
