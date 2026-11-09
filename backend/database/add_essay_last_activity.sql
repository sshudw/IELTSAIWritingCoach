ALTER TABLE essay
    ADD COLUMN last_activity_at DATETIME(6) NULL AFTER created_at;

UPDATE essay AS e
LEFT JOIN (
    SELECT essay_id, MAX(created_at) AS latest_message_at
    FROM essay_message
    GROUP BY essay_id
) AS m ON m.essay_id = e.id
SET e.last_activity_at = CASE
    WHEN m.latest_message_at IS NULL
        THEN COALESCE(e.created_at, CURRENT_TIMESTAMP(6))
    WHEN e.created_at IS NULL
        THEN m.latest_message_at
    ELSE GREATEST(e.created_at, m.latest_message_at)
END;

ALTER TABLE essay
    MODIFY COLUMN last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

CREATE INDEX idx_essay_user_activity
    ON essay(user_id, last_activity_at DESC, id DESC);
