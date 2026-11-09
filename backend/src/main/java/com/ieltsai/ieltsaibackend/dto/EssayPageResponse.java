package com.ieltsai.ieltsaibackend.dto;

import java.util.List;

public record EssayPageResponse(
        int page,
        int pageSize,
        long total,
        long totalPages,
        List<EssaySummaryResponse> essays
) {
}
