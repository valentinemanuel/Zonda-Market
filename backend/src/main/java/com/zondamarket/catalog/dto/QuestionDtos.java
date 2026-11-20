package com.zondamarket.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QuestionDtos {
    private QuestionDtos() {}

    public record QuestionRequest(@NotBlank @Size(max = 500) String text) {}
    public record AnswerRequest(@NotBlank @Size(max = 1000) String text) {}
    public record Question(UUID id, String text, Instant createdAt, String answer, Instant answeredAt) {}
    public record PendingQuestion(UUID id, String text, Instant createdAt, String listingSlug, String listingTitle) {}
    public record QuestionPage<T>(List<T> content, int page, boolean hasNext) {}
}
