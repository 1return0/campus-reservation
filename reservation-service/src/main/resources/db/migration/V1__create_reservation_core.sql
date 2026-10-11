CREATE TABLE reservable_resource (
    id BIGINT NOT NULL,
    resource_code VARCHAR(64) NOT NULL,
    resource_name VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservable_resource_code (resource_code),
    CONSTRAINT ck_reservable_resource_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resource_slot (
    id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    capacity INT NOT NULL,
    available_capacity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_slot_window (resource_id, starts_at, ends_at),
    KEY ix_resource_slot_reserve (resource_id, status, starts_at),
    CONSTRAINT fk_resource_slot_resource FOREIGN KEY (resource_id) REFERENCES reservable_resource (id),
    CONSTRAINT ck_resource_slot_capacity CHECK (capacity > 0),
    CONSTRAINT ck_resource_slot_available_capacity CHECK (available_capacity >= 0 AND available_capacity <= capacity),
    CONSTRAINT ck_resource_slot_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_resource_slot_status CHECK (status IN ('OPEN', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reservation_order (
    id BIGINT NOT NULL,
    reservation_no CHAR(32) NOT NULL,
    slot_id BIGINT NOT NULL,
    requester_reference VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_marker TINYINT NULL,
    cancelled_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_no (reservation_no),
    UNIQUE KEY uk_reservation_idempotency (requester_reference, idempotency_key),
    UNIQUE KEY uk_reservation_active_requester_slot (slot_id, requester_reference, active_marker),
    KEY ix_reservation_requester (requester_reference, created_at DESC),
    CONSTRAINT fk_reservation_order_slot FOREIGN KEY (slot_id) REFERENCES resource_slot (id),
    CONSTRAINT ck_reservation_order_status CHECK (status IN ('PENDING', 'CONFIRMED', 'WAITLISTED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reservation_waitlist_entry (
    id BIGINT NOT NULL,
    reservation_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    requester_reference VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    promoted_at DATETIME(3) NULL,
    cancelled_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_waitlist_reservation (reservation_id),
    KEY ix_waitlist_claim (slot_id, status, created_at, id),
    CONSTRAINT fk_waitlist_reservation FOREIGN KEY (reservation_id) REFERENCES reservation_order (id),
    CONSTRAINT fk_waitlist_slot FOREIGN KEY (slot_id) REFERENCES resource_slot (id),
    CONSTRAINT ck_waitlist_status CHECK (status IN ('WAITING', 'PROMOTED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
