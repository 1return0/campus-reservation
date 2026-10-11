ALTER TABLE study_room_booking
    ADD COLUMN required_attendance_count INT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_study_booking_required_attendance
        CHECK (required_attendance_count BETWEEN 1 AND 20);

-- Preserve the one-person rule for already fulfilled legacy bookings. Open
-- bookings adopt their room's current minimum before anyone checks in.
UPDATE study_room_booking booking
JOIN reservable_resource room ON room.id = booking.resource_id
SET booking.required_attendance_count = room.min_participants
WHERE booking.status IN ('CONFIRMED', 'WAITLISTED');

ALTER TABLE study_room_booking_member
    ADD COLUMN checked_in_at DATETIME(3) NULL,
    ADD COLUMN checked_in_by_reference VARCHAR(64) NULL;

-- Keep the one recorded attendee of a pre-V11 checked-in booking visible.
-- Do not invent attendance records for its other participants.
UPDATE study_room_booking_member member
JOIN study_room_booking booking ON booking.id = member.booking_id
SET member.checked_in_at = booking.checked_in_at,
    member.checked_in_by_reference = booking.handled_by_reference
WHERE booking.status IN ('CHECKED_IN', 'COMPLETED')
  AND booking.checked_in_student_number = member.student_number
  AND booking.checked_in_at IS NOT NULL;
