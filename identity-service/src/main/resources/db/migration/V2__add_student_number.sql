ALTER TABLE identity_user
    ADD COLUMN student_number VARCHAR(32) NULL AFTER username,
    ADD UNIQUE KEY uk_identity_user_student_number (student_number);
