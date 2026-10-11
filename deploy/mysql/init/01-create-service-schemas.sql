CREATE DATABASE IF NOT EXISTS campus_identity
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS campus_notification
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

GRANT ALL PRIVILEGES ON campus_reserve.* TO 'campus_app'@'%';
GRANT ALL PRIVILEGES ON campus_identity.* TO 'campus_app'@'%';
GRANT ALL PRIVILEGES ON campus_notification.* TO 'campus_app'@'%';
FLUSH PRIVILEGES;
