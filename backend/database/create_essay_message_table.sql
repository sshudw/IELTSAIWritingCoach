CREATE TABLE IF NOT EXISTS essay_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    essay_id BIGINT NOT NULL,
    `role` VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_essay_message_order (essay_id, created_at, id),
    CONSTRAINT fk_essay_message_essay
        FOREIGN KEY (essay_id) REFERENCES essay(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);
