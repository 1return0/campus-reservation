ALTER TABLE reservation_order
    DROP CHECK ck_reservation_order_status;

ALTER TABLE reservation_order
    ADD COLUMN checked_in_at DATETIME(3) NULL AFTER cancelled_at,
    ADD COLUMN completed_at DATETIME(3) NULL AFTER checked_in_at,
    ADD COLUMN no_show_at DATETIME(3) NULL AFTER completed_at,
    ADD COLUMN handled_by_reference VARCHAR(64) NULL AFTER no_show_at,
    ADD CONSTRAINT ck_reservation_order_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'WAITLISTED', 'CANCELLED', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW'));

CREATE INDEX ix_reservation_work_queue
    ON reservation_order (status, created_at DESC, id DESC);
