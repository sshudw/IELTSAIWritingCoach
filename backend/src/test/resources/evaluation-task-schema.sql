-- Test-only H2 schema; production migration is database/create_evaluation_task_table.sql.
CREATE TABLE IF NOT EXISTS essay (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    content TEXT NOT NULL,
    essay_type VARCHAR(20) NOT NULL,
    word_count INT,
    evaluation_status VARCHAR(20) NOT NULL,
    overall_score DECIMAL(2,1), task_score DECIMAL(2,1), coherence_score DECIMAL(2,1),
    lexical_score DECIMAL(2,1), grammar_score DECIMAL(2,1),
    ai_feedback TEXT, ai_model VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS evaluation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    essay_id BIGINT NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, idempotency_key),
    CHECK(status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    FOREIGN KEY(essay_id) REFERENCES essay(id) ON DELETE CASCADE
);
