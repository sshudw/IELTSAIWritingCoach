ALTER TABLE essay
    ADD COLUMN overall_score DECIMAL(2, 1) NULL AFTER word_count,
    ADD COLUMN task_score DECIMAL(2, 1) NULL AFTER overall_score,
    ADD COLUMN coherence_score DECIMAL(2, 1) NULL AFTER task_score,
    ADD COLUMN lexical_score DECIMAL(2, 1) NULL AFTER coherence_score,
    ADD COLUMN grammar_score DECIMAL(2, 1) NULL AFTER lexical_score,
    ADD COLUMN evaluation_status VARCHAR(20) NOT NULL DEFAULT 'NOT_EVALUATED' AFTER grammar_score,
    ADD COLUMN ai_feedback TEXT NULL AFTER evaluation_status,
    ADD COLUMN ai_model VARCHAR(100) NULL AFTER ai_feedback;
