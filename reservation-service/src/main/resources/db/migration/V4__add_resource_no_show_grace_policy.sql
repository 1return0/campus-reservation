ALTER TABLE reservable_resource
    ADD COLUMN no_show_grace_minutes INT NOT NULL DEFAULT 15 AFTER resource_type,
    ADD CONSTRAINT ck_reservable_resource_no_show_grace
        CHECK (no_show_grace_minutes BETWEEN 1 AND 60);
