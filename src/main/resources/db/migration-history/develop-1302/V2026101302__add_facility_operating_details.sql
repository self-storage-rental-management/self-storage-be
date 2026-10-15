ALTER TABLE facilities
    ADD COLUMN phone VARCHAR(32) NULL AFTER city,
    ADD COLUMN access_hours VARCHAR(120) NULL AFTER phone,
    ADD COLUMN image_url LONGTEXT NULL AFTER access_hours,
    ADD COLUMN climate_controlled BOOLEAN NOT NULL DEFAULT FALSE AFTER image_url,
    ADD COLUMN security_description VARCHAR(255) NULL AFTER climate_controlled;
