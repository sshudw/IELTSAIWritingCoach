package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.AiEvaluationResult;
import com.ieltsai.ieltsaibackend.dto.CreateEssayRequest;
import com.ieltsai.ieltsaibackend.dto.EssayPageResponse;
import com.ieltsai.ieltsaibackend.dto.EssaySummaryResponse;
import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.entity.EvaluationStatus;
import com.ieltsai.ieltsaibackend.mapper.EssayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class EssayService {

    private static final Logger log = LoggerFactory.getLogger(EssayService.class);

    private final EssayMapper essayMapper;

    public EssayService(EssayMapper essayMapper) {
        this.essayMapper = essayMapper;
    }

    public void createEssay(Long currentUserId, CreateEssayRequest request) {
        Essay essay = buildEssay(currentUserId, request);
        essay.setEvaluationStatus(EvaluationStatus.NOT_EVALUATED);
        essayMapper.insert(essay);
        log.info(
                "event=essay_created userId={} essayId={} essayType={} wordCount={}",
                currentUserId, essay.getId(), essay.getEssayType(), essay.getWordCount()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Essay createPendingEssay(Long currentUserId, CreateEssayRequest request) {
        Essay essay = buildEssay(currentUserId, request);
        essay.setEvaluationStatus(EvaluationStatus.PENDING);
        essayMapper.insert(essay);

        if (essay.getId() == null) {
            throw new IllegalStateException("Database did not return the generated essay id");
        }

        return essay;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveEvaluationResult(Essay essay, AiEvaluationResult aiResult) {
        applyAiResult(essay, aiResult);
        essay.setEvaluationStatus(EvaluationStatus.COMPLETED);
        requireOneUpdatedRow(essayMapper.updateEvaluation(essay));
    }

    public Essay getMyEssayById(Long currentUserId, Long essayId) {
        return essayMapper.findByIdAndUserId(essayId, currentUserId);
    }

    @Transactional(readOnly = true)
    public EssayPageResponse getMyEssays(Long currentUserId, int page, int pageSize) {
        try {
            long total = essayMapper.countByUserId(currentUserId);
            long offset = (long) (page - 1) * pageSize;
            List<EssaySummaryResponse> essays = essayMapper
                    .findPageByUserId(currentUserId, pageSize, offset)
                    .stream()
                    .map(this::toSummary)
                    .toList();
            long totalPages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
            return new EssayPageResponse(page, pageSize, total, totalPages, essays);
        } catch (RuntimeException exception) {
            // The global handler logs the stack after this exception is rethrown.
            log.error(
                    "event=essay_page_query_failed userId={} page={} pageSize={}",
                    currentUserId, page, pageSize
            );
            throw exception;
        }
    }

    @Transactional
    public boolean deleteMyEssay(Long currentUserId, Long essayId) {
        boolean deleted = essayMapper.deleteByIdAndUserId(essayId, currentUserId) == 1;
        if (deleted) {
            log.info(
                    "event=essay_conversation_deleted userId={} essayId={}",
                    currentUserId, essayId
            );
        }
        return deleted;
    }

    private EssaySummaryResponse toSummary(Essay essay) {
        return new EssaySummaryResponse(
                essay.getId(),
                essay.getQuestion(),
                essay.getEssayType(),
                essay.getWordCount(),
                essay.getOverallScore(),
                essay.getEvaluationStatus(),
                essay.getLastActivityAt()
        );
    }

    private Essay buildEssay(Long currentUserId, CreateEssayRequest request) {
        Essay essay = new Essay();
        essay.setUserId(currentUserId);
        essay.setQuestion(request.question());
        essay.setContent(request.content());
        essay.setEssayType(request.essayType());
        essay.setWordCount(countWords(essay.getContent()));
        return essay;
    }

    private void applyAiResult(Essay essay, AiEvaluationResult aiResult) {
        essay.setTaskScore(aiResult.taskScore());
        essay.setCoherenceScore(aiResult.coherenceScore());
        essay.setLexicalScore(aiResult.lexicalScore());
        essay.setGrammarScore(aiResult.grammarScore());
        essay.setOverallScore(calculateOverallScore(aiResult));
        essay.setAiFeedback(aiResult.aiFeedback());
        essay.setAiModel(aiResult.aiModel());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markEvaluationFailed(Essay essay, String model) {
        essay.setOverallScore(null);
        essay.setTaskScore(null);
        essay.setCoherenceScore(null);
        essay.setLexicalScore(null);
        essay.setGrammarScore(null);
        essay.setAiFeedback(null);
        essay.setAiModel(model);
        essay.setEvaluationStatus(EvaluationStatus.FAILED);
        requireOneUpdatedRow(essayMapper.markEvaluationFailed(
                essay.getId(),
                essay.getUserId(),
                essay.getAiModel()
        ));
    }

    private void requireOneUpdatedRow(int updatedRows) {
        if (updatedRows != 1) {
            throw new IllegalStateException("Essay evaluation status was not saved");
        }
    }

    private BigDecimal calculateOverallScore(AiEvaluationResult result) {
        BigDecimal sum = result.taskScore()
                .add(result.coherenceScore())
                .add(result.lexicalScore())
                .add(result.grammarScore());
        BigDecimal average = sum.divide(BigDecimal.valueOf(4), 3, RoundingMode.HALF_UP);
        return average.multiply(BigDecimal.valueOf(2))
                .setScale(0, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(2))
                .setScale(1);
    }

    private int countWords(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.trim().split("\\s+").length;
    }

}
