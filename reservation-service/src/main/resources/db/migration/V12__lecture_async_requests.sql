-- Admission sequencing is deliberately independent of resource_slot: accepting a
-- request must not wait for the full reservation/maintenance transaction.
CREATE TABLE lecture_request_sequence (
    slot_id BIGINT NOT NULL PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE lecture_reservation_request (
    id BIGINT NOT NULL PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    slot_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    requester_reference VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
    reservation_id BIGINT NULL,
    failure_code VARCHAR(100) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    next_wakeup_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_lecture_request_id (request_id),
    UNIQUE KEY uk_lecture_request_replay (requester_reference, idempotency_key),
    UNIQUE KEY uk_lecture_request_sequence (slot_id, sequence_no),
    KEY ix_lecture_request_head (slot_id, status, sequence_no),
    KEY ix_lecture_request_recovery (status, next_wakeup_at),
    CONSTRAINT ck_lecture_request_status CHECK (status IN ('PROCESSING','CONFIRMED','WAITLISTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
