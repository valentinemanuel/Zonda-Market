package com.zondamarket.catalog.controller;

import com.zondamarket.catalog.dto.QuestionDtos.*;
import com.zondamarket.catalog.service.QuestionService;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Preguntas sobre publicaciones")
class QuestionController {
    private final QuestionService questions;

    QuestionController(QuestionService questions) {
        this.questions = questions;
    }

    @GetMapping("/api/v1/listings/{id}/questions")
    ResponseEntity<QuestionPage<Question>> list(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(48) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(questions.list(id, page, size));
    }

    @PostMapping("/api/v1/listings/{id}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    Question ask(@PathVariable UUID id, @AuthenticationPrincipal AccountResponse account,
                 @Valid @RequestBody QuestionRequest body, HttpServletResponse response) {
        return questions.ask(id, account.id(), body, trace(response));
    }

    @GetMapping("/api/v1/seller/questions")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<QuestionPage<PendingQuestion>> queue(@AuthenticationPrincipal AccountResponse account,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(48) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(questions.queue(account, page, size));
    }

    @PutMapping("/api/v1/seller/questions/{id}/answer")
    @SecurityRequirement(name = "bearerAuth")
    Question answer(@PathVariable UUID id, @AuthenticationPrincipal AccountResponse account,
                    @Valid @RequestBody AnswerRequest body, HttpServletResponse response) {
        return questions.answer(account.id(), id, body, trace(response));
    }

    private static UUID trace(HttpServletResponse response) {
        return UUID.fromString(response.getHeader(CorrelationIdFilter.TRACE_HEADER));
    }
}
