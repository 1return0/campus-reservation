CREATE TABLE study_team (
    id BIGINT NOT NULL,
    leader_reference VARCHAR(64) NOT NULL,
    invite_code_hash CHAR(64) NOT NULL,
    invite_code_expires_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_study_team_invite_code_hash (invite_code_hash),
    KEY ix_study_team_leader (leader_reference, created_at DESC),
    CONSTRAINT ck_study_team_status CHECK (status IN ('FORMING', 'BOOKED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_team_member (
    id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    student_number VARCHAR(32) NOT NULL,
    user_reference VARCHAR(64) NULL,
    status VARCHAR(24) NOT NULL,
    accepted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_study_team_member_number (team_id, student_number),
    UNIQUE KEY uk_study_team_member_user (team_id, user_reference),
    KEY ix_study_team_member_invited (student_number, status, created_at),
    CONSTRAINT fk_study_team_member_team FOREIGN KEY (team_id) REFERENCES study_team (id),
    CONSTRAINT ck_study_team_member_status CHECK (status IN ('INVITED', 'ACCEPTED', 'DECLINED', 'LEFT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
