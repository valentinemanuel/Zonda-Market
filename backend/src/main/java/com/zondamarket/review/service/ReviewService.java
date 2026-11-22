package com.zondamarket.review.service;

import com.zondamarket.checkout.repository.FulfillmentStore;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.repository.SellerProfileRepository;
import com.zondamarket.review.dto.ReviewDtos.ReviewPage;
import com.zondamarket.review.dto.ReviewDtos.ReviewRequest;
import com.zondamarket.review.dto.ReviewDtos.ReviewResponse;
import com.zondamarket.review.dto.ReviewDtos.SellerReputationResponse;
import com.zondamarket.review.repository.ReviewStore;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {
    private final ReviewStore reviews;
    private final FulfillmentStore fulfillment;
    private final SellerProfileRepository sellers;
    private final Clock clock;

    ReviewService(ReviewStore reviews, FulfillmentStore fulfillment, SellerProfileRepository sellers, Clock clock) {
        this.reviews = reviews;
        this.fulfillment = fulfillment;
        this.sellers = sellers;
        this.clock = clock;
    }

    @Transactional
    public ReviewResponse create(UUID buyer, UUID id, ReviewRequest request, UUID trace) {
        var order = fulfillment.lockParent(id, buyer, false).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "SUB_ORDER_NOT_FOUND", "No encontramos esa suborden."));
        var sub = fulfillment.lockSubOrder(id);
        if (!"PAGADA".equals(order.status()) || !"ENTREGADA".equals(sub.status())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "REVIEW_DELIVERY_REQUIRED",
                    "Confirmá la recepción de la suborden para calificarla.");
        }
        String comment = request.comment() == null ? "" : request.comment().strip();
        if (comment.indexOf('\0') >= 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Revisá el texto de tu comentario.");
        }
        return reviews.insert(id, buyer, request.rating().intValueExact(), comment.isEmpty() ? null : comment, clock.instant(), trace)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.CONFLICT, "REVIEW_ALREADY_EXISTS",
                        "Ya publicaste una reseña de esta suborden."));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewPage forSeller(String slug, int page, int size) {
        var seller = sellers.findBySlugAndDeletedFalseAndAccountDeletedFalseAndAccountRole(slug, Role.SELLER)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "No encontramos ese vendedor."));
        var content = reviews.list(seller.getId(), page, size);
        boolean hasNext = content.size() > size;
        return new ReviewPage(hasNext ? content.subList(0, size) : content, page, size, hasNext, reputation(seller.getId()));
    }

    public SellerReputationResponse reputation(UUID seller) {
        return reviews.reputation(seller);
    }
}
