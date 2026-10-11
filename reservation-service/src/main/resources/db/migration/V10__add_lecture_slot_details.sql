ALTER TABLE resource_slot
    ADD COLUMN event_title VARCHAR(160) NULL,
    ADD COLUMN speaker_name VARCHAR(120) NULL,
    ADD COLUMN activity_description VARCHAR(2000) NULL;
