CREATE TABLE IF NOT EXISTS agent_system_config (
    id           BIGSERIAL PRIMARY KEY,
    config_group VARCHAR(64)  NOT NULL,
    config_key   VARCHAR(128) NOT NULL UNIQUE,
    config_value TEXT,
    is_secret    BOOLEAN      NOT NULL DEFAULT FALSE,
    description  VARCHAR(255),
    delete_flag  SMALLINT     NOT NULL DEFAULT 0,
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW()
);

INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description) VALUES
('security',   'crypto.aes-key',              '',  true,  'AES-256-GCM encryption key (auto-generated, base64)'),
('chrome',     'chrome.extension-token',      '',  false, 'Chrome extension callback authentication token'),
('web-read',   'web-read.jina-api-key',       '',  true,  'Jina Reader API key for web page reading'),
('rate-limit', 'rate-limit.login-max-attempts', '5', false, 'Maximum login attempts per time window'),
('rate-limit', 'rate-limit.login-window-minutes','1', false, 'Login rate limit time window (minutes)');
