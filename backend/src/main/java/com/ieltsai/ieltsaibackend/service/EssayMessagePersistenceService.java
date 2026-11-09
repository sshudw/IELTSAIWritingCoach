package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import com.ieltsai.ieltsaibackend.mapper.EssayMapper;
import com.ieltsai.ieltsaibackend.mapper.EssayMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class EssayMessagePersistenceService {

    private final EssayMessageMapper essayMessageMapper;
    private final EssayMapper essayMapper;

    public EssayMessagePersistenceService(
            EssayMessageMapper essayMessageMapper,
            EssayMapper essayMapper
    ) {
        this.essayMessageMapper = essayMessageMapper;
        this.essayMapper = essayMapper;
    }

    // Called through a separate Spring bean: the commit completes before the caller invokes AI.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EssayMessage saveUserMessage(Long currentUserId, Long essayId, String content) {
        LocalDateTime activityTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (essayMapper.updateLastActivity(essayId, currentUserId, activityTime) != 1) {
            throw new IllegalStateException("Essay does not belong to the current user");
        }
        return saveMessage(essayId, "USER", content);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EssayMessage saveAssistantMessage(Long essayId, String content) {
        return saveMessage(essayId, "ASSISTANT", content);
    }

    private EssayMessage saveMessage(Long essayId, String role, String content) {
        EssayMessage message = new EssayMessage();
        message.setEssayId(essayId);
        message.setRole(role);
        message.setContent(content);
        // Match the existing MySQL DATETIME column's second precision.
        message.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        essayMessageMapper.insert(message);
        if (message.getId() == null) {
            throw new IllegalStateException("Database did not return the generated message id");
        }
        return message;
    }
}
