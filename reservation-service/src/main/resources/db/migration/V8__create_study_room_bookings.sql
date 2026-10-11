ALTER TABLE reservable_resource
    ADD COLUMN min_participants INT NOT NULL DEFAULT 1,
    ADD COLUMN max_participants INT NOT NULL DEFAULT 8;

CREATE TABLE study_room_open_window (
    id BIGINT NOT NULL PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY ix_study_room_window_resource (resource_id, starts_at, ends_at),
    CONSTRAINT fk_study_room_window_resource FOREIGN KEY (resource_id) REFERENCES reservable_resource(id),
    CONSTRAINT ck_study_room_window_interval CHECK (starts_at < ends_at),
    CONSTRAINT ck_study_room_window_status CHECK (status IN ('OPEN', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_room_booking (
    id BIGINT NOT NULL PRIMARY KEY,
    booking_no VARCHAR(32) NOT NULL,
    resource_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    leader_reference VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    cancellation_reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_study_booking_no (booking_no),
    UNIQUE KEY uk_study_booking_replay (leader_reference, idempotency_key),
    KEY ix_study_booking_room_interval (resource_id, status, starts_at, ends_at),
    KEY ix_study_booking_team (team_id, status),
    CONSTRAINT fk_study_booking_resource FOREIGN KEY (resource_id) REFERENCES reservable_resource(id),
    CONSTRAINT fk_study_booking_team FOREIGN KEY (team_id) REFERENCES study_team(id),
    CONSTRAINT ck_study_booking_interval CHECK (starts_at < ends_at),
    CONSTRAINT ck_study_booking_status CHECK (status IN ('CONFIRMED', 'WAITLISTED', 'CANCELLED', 'CANCELLED_BY_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_room_booking_member (
    id BIGINT NOT NULL PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    user_reference VARCHAR(64) NOT NULL,
    student_number VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_study_booking_member (booking_id, student_number),
    CONSTRAINT fk_study_booking_member_booking FOREIGN KEY (booking_id) REFERENCES study_room_booking(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A confirmed participant owns every 30-minute bucket in the booking interval.
-- This unique key closes cross-room races even when the room locks differ.
CREATE TABLE study_room_member_bucket (
    student_number VARCHAR(32) NOT NULL,
    bucket_start DATETIME(3) NOT NULL,
    booking_id BIGINT NOT NULL,
    PRIMARY KEY (student_number, bucket_start),
    KEY ix_study_bucket_booking (booking_id),
    CONSTRAINT fk_study_bucket_booking FOREIGN KEY (booking_id) REFERENCES study_room_booking(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
