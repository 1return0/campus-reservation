CREATE TABLE notification_inbox_event (
    id BIGINT NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_inbox_event_id (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE student_notification (
    id BIGINT NOT NULL,
    notification_no CHAR(32) NOT NULL,
    recipient_reference VARCHAR(64) NOT NULL,
    source_event_id CHAR(36) NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(512) NOT NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_notification_no (notification_no),
    UNIQUE KEY uk_student_notification_event (source_event_id),
    KEY ix_student_notification_recipient (recipient_reference, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
