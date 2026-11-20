package com.zondamarket.catalog.service;

import com.zondamarket.catalog.dto.QuestionDtos.*;
import com.zondamarket.catalog.repository.QuestionStore;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
    private final QuestionStore store;
    private final Clock clock;

    QuestionService(QuestionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QuestionPage<Question> list(UUID listing, int page, int size) {
        if (!store.visible(listing)) {
            throw missingListing();
        }
        return page(store.list(listing, (long) page * size, size + 1), page, size);
    }

    @Transactional
    public Question ask(UUID listing, UUID actor, QuestionRequest request, UUID trace) {
        if (!store.visible(listing) || !store.lockActiveListing(listing) || !store.visible(listing)) {
            throw missingListing();
        }
        String text = text(request.text());
        Instant now = clock.instant();
        consumeBudget(actor, "QUESTION", now, 10);
        return store.add(listing, actor, text, now, trace);
    }

    @Transactional(readOnly = true)
    public QuestionPage<PendingQuestion> queue(AccountResponse actor, int page, int size) {
        if (!actor.roles().contains(Role.SELLER)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "SELLER_REQUIRED", "Activá tu perfil de vendedor.");
        }
        return page(store.pending(actor.id(), (long) page * size, size + 1), page, size);
    }

    @Transactional
    public Question answer(UUID actor, UUID id, AnswerRequest request, UUID trace) {
        UUID listing = store.ownedListing(actor, id).orElseThrow(QuestionService::missingQuestion);
        if (!store.lockActiveListing(listing) || store.ownedListing(actor, id).isEmpty()) {
            throw missingQuestion();
        }
        Question question = store.find(id).orElseThrow(QuestionService::missingQuestion);
        String text = text(request.text());
        if (question.answer() != null) {
            if (question.answer().equals(text)) {
                return question;
            }
            throw new ApiProblemException(HttpStatus.CONFLICT, "QUESTION_ALREADY_ANSWERED", "Esta pregunta ya tiene respuesta.");
        }
        Instant now = clock.instant();
        consumeBudget(actor, "ANSWER", now, 60);
        return store.answer(id, actor, text, now, trace);
    }

    private void consumeBudget(UUID actor, String action, Instant now, int maximum) {
        if (!store.consumeBudget(actor, action, now.truncatedTo(ChronoUnit.HOURS), maximum)) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, "QUESTION_RATE_LIMIT",
                    "Alcanzaste el límite por hora. Volvé a intentar más tarde.");
        }
    }

    private static String text(String raw) {
        String value = raw.strip();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Escribí un mensaje en texto plano.");
        }
        return value;
    }

    private static <T> QuestionPage<T> page(List<T> rows, int page, int size) {
        boolean hasNext = rows.size() > size;
        return new QuestionPage<>(hasNext ? rows.subList(0, size) : rows, page, hasNext);
    }

    private static ApiProblemException missingListing() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", "No encontramos esa publicación.");
    }

    private static ApiProblemException missingQuestion() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "No encontramos esa pregunta.");
    }
}
