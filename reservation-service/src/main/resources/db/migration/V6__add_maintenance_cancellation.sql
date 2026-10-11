ALTER TABLE resource_slot
    DROP CHECK ck_resource_slot_status;

ALTER TABLE resource_slot
    ADD CONSTRAINT ck_resource_slot_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED'));

ALTER TABLE reservation_order
    DROP CHECK ck_reservation_order_status;

ALTER TABLE reservation_order
    ADD CONSTRAINT ck_reservation_order_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'WAITLISTED', 'CANCELLED',
                   'CANCELLED_BY_ADMIN', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW')
    );

CREATE TABLE slot_cancellation (
    slot_id BIGINT NOT NULL,
    operator_reference VARCHAR(64) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    confirmed_count INT NOT NULL,
    waitlisted_count INT NOT NULL,
    cancelled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (slot_id),
    CONSTRAINT fk_slot_cancellation_slot FOREIGN KEY (slot_id) REFERENCES resource_slot (id),
    CONSTRAINT ck_slot_cancellation_counts CHECK (confirmed_count >= 0 AND waitlisted_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
