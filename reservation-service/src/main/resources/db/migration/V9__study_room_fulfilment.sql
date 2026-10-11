ALTER TABLE study_room_booking
    ADD COLUMN handled_by_reference VARCHAR(64) NULL,
    ADD COLUMN checked_in_student_number VARCHAR(32) NULL,
    ADD COLUMN checked_in_at DATETIME(3) NULL,
    ADD COLUMN completed_at DATETIME(3) NULL,
    ADD COLUMN no_show_at DATETIME(3) NULL;

ALTER TABLE study_room_booking DROP CHECK ck_study_booking_status;
ALTER TABLE study_room_booking ADD CONSTRAINT ck_study_booking_status
    CHECK (status IN ('CONFIRMED', 'WAITLISTED', 'CANCELLED',
                     'CANCELLED_BY_ADMIN', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW'));
