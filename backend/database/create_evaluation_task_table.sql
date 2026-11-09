-- Required by POST /api/essays/evaluate and GET /api/evaluation-tasks/{taskId}.
-- Apply once after add_ai_evaluation_columns.sql, create_essay_message_table.sql,
-- and add_essay_last_activity.sql. Phase one supports exactly one application instance.
-- essay.id is signed BIGINT (same convention as essay_message).
CREATE TABLE IF NOT EXISTS evaluation_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    essay_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_evaluation_task_user_key (user_id, idempotency_key),
    UNIQUE KEY uk_evaluation_task_essay (essay_id),
    INDEX idx_evaluation_task_pending (status, id),
    CONSTRAINT chk_evaluation_task_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_evaluation_task_essay FOREIGN KEY (essay_id)
        REFERENCES essay(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
