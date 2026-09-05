ALTER TABLE site_config
    ADD CONSTRAINT chk_site_config_version_range CHECK (version >= 0);

CREATE TABLE portable_import_guard (
    id INTEGER PRIMARY KEY,
    CONSTRAINT chk_portable_import_guard_singleton CHECK (id = 1)
);
INSERT INTO portable_import_guard (id) VALUES (1);

CREATE TABLE portable_import_operation (
    job_id VARCHAR(64) PRIMARY KEY,
    preview_token VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NOT NULL,
    committed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    site_version INTEGER NOT NULL,
    CONSTRAINT uk_portable_import_preview UNIQUE (preview_token)
);
CREATE INDEX idx_portable_import_user_committed
    ON portable_import_operation (user_id, committed_at DESC);
