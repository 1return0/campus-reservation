-- Give existing study rooms consistent names; IDs and all booking relations remain unchanged.
-- ROW_NUMBER forces materialization of the derived table in MySQL 8.
UPDATE reservable_resource AS resource
JOIN (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS room_number
    FROM reservable_resource
    WHERE resource_type = 'STUDY_ROOM'
) AS numbered ON numbered.id = resource.id
SET resource.resource_name = CONCAT('研讨室-', numbered.room_number);
