ALTER TABLE site_config
    ADD COLUMN IF NOT EXISTS install_completed_at TIMESTAMP;

ALTER TABLE site_config
    DISABLE TRIGGER trg_site_config_updated_at;

UPDATE site_config
SET install_completed_at = CURRENT_TIMESTAMP
WHERE install_completed_at IS NULL
  AND EXISTS (SELECT 1 FROM sys_user);

ALTER TABLE site_config
    ENABLE TRIGGER trg_site_config_updated_at;
