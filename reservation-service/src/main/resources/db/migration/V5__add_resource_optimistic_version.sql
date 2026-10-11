ALTER TABLE reservable_resource
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER status;
