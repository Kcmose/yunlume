ALTER TABLE site_config
    ADD COLUMN IF NOT EXISTS install_instance_id UUID;

ALTER TABLE site_config
    DISABLE TRIGGER trg_site_config_updated_at;

UPDATE site_config
SET install_instance_id = gen_random_uuid()
WHERE install_instance_id IS NULL;

ALTER TABLE site_config
    ALTER COLUMN install_instance_id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN install_instance_id SET NOT NULL;

ALTER TABLE site_config
    ENABLE TRIGGER trg_site_config_updated_at;
