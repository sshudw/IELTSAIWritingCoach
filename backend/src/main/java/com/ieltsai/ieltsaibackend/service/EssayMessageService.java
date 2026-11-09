package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.entity.EssayMessage;
import com.ieltsai.ieltsaibackend.mapper.EssayMapper;
import com.ieltsai.ieltsaibackend.mapper.EssayMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class EssayMessageService {

    private static final Logger log = LoggerFactory.getLogger(EssayMessageService.class);

    private final EssayMapper essayMapper;
    private final EssayMessageMapper essayMessageMapper;
    private final EssayMessagePersistenceService persistenceService;
    private final AiChatService aiChatService;
    private final EssayChatContextBuilder contextBuilder;

    public EssayMessageService(
            EssayMapper essayMapper,
            EssayMessageMapper essayMessageMapper,
            EssayMessagePersistenceService persistenceService,
            AiChatService aiChatService,
            EssayChatContextBuilder contextBuilder
    ) {
        this.essayMapper = essayMapper;
        this.essayMessageMapper = essayMessageMapper;
        this.persistenceService = persistenceService;
        this.aiChatService = aiChatService;
        this.contextBuilder = contextBuilder;
    }

    public List<EssayMessage> getMessages(Long currentUserId, Long essayId) {
        if (essayMapper.findByIdAndUserId(essayId, currentUserId) == null) {
            return null;
        }

        return essayMessageMapper.findByEssayId(essayId);
    }

    // Suspend any caller transaction, so no database transaction spans the HTTP call.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatResult createUserMessage(Long currentUserId, Long essayId, String content) {
        Essay essay = essayMapper.findByIdAndUserId(essayId, currentUserId);
        if (essay == null) {
            return null;
        }

        // This call returns only after its REQUIRES_NEW transaction has committed.
        EssayMessage userMessage = persistenceService.saveUserMessage(currentUserId, essayId, content);
        log.info(
                "event=ai_chat_user_saved userId={} essayId={} userMessageId={}",
                currentUserId, essayId, userMessage.getId()
        );
        long aiStartedAt = 0L;
        try {
            List<EssayMessage> history = essayMessageMapper.findRecentBefore(essayId, userMessage.getId());
            aiStartedAt = System.nanoTime();
            log.info(
                    "event=ai_chat_deepseek_started userId={} essayId={} userMessageId={}",
                    currentUserId, essayId, userMessage.getId()
            );
            AiChatService.ChatReply reply = aiChatService.chat(contextBuilder.build(essay, history, userMessage));
            log.info(
                    "event=ai_chat_deepseek_succeeded userId={} essayId={} userMessageId={} model={} durationMs={}",
                    currentUserId, essayId, userMessage.getId(), reply.aiModel(), elapsedMillis(aiStartedAt)
            );
            EssayMessage assistantMessage = persistenceService.saveAssistantMessage(essayId, reply.content());
            log.info(
                    "event=ai_chat_assistant_saved userId={} essayId={} userMessageId={} assistantMessageId={}",
                    currentUserId, essayId, userMessage.getId(), assistantMessage.getId()
            );
            return new ChatResult(userMessage, assistantMessage, reply.aiModel(), 201, null);
        } catch (AiChatService.AiChatException exception) {
            String event = exception.isTimeout()
                    ? "ai_chat_deepseek_timed_out"
                    : "ai_chat_deepseek_failed";
            int status = exception.isTimeout() ? 504 : 502;
            log.warn(
                    "event={} userId={} essayId={} userMessageId={} status={} durationMs={}",
                    event, currentUserId, essayId, userMessage.getId(), status,
                    elapsedMillis(aiStartedAt), exception
            );
            return new ChatResult(userMessage, null, null, exception.isTimeout() ? 504 : 502,
                    exception.isTimeout()
                            ? "USER message saved, but AI reply timed out"
                            : "USER message saved, but AI reply failed");
        } catch (DataAccessException | TransactionException exception) {
            // This exception becomes a 500 result, so the global handler will not see it.
            log.error(
                    "event=ai_chat_completion_failed userId={} essayId={} userMessageId={}",
                    currentUserId, essayId, userMessage.getId(), exception
            );
            return new ChatResult(userMessage, null, null, 500,
                    "USER message saved, but the conversation could not be completed");
        }
    }

    private long elapsedMillis(long startedAt) {
        if (startedAt == 0L) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public record ChatResult(
            EssayMessage userMessage,
            EssayMessage assistantMessage,
            String aiModel,
            int status,
            String message
    ) {
    }
}
