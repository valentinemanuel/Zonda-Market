package com.zondamarket.catalog.service;

import com.zondamarket.catalog.dto.StockDtos.AdjustmentRequest;
import com.zondamarket.catalog.dto.StockDtos.Movement;
import com.zondamarket.catalog.dto.StockDtos.MovementPage;
import com.zondamarket.catalog.repository.ListingSkuRepository;
import com.zondamarket.catalog.repository.StockMovementStore;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {
    private final ListingService listings;
    private final ListingSkuRepository skus;
    private final StockMovementStore movements;
    private final Clock clock;

    StockService(ListingService listings, ListingSkuRepository skus, StockMovementStore movements, Clock clock) {
        this.listings = listings;
        this.skus = skus;
        this.movements = movements;
        this.clock = clock;
    }

    @Transactional
    public Movement adjust(UUID actor, UUID skuId, String key, AdjustmentRequest request, UUID traceId) {
        requireOwner(actor, skuId);
        int delta = request.delta().intValueExact();
        String reason = request.reason().strip();
        long expectedVersion = request.expectedVersion().longValueExact();
        if (delta == 0 || reason.isBlank() || reason.indexOf(0) >= 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "STOCK_ADJUSTMENT_INVALID",
                    "Ingresá un ajuste distinto de cero y un motivo válido.");
        }

        var sku = skus.findLockedByIdAndDeletedFalse(skuId).orElseThrow(StockService::missingSku);
        var recorded = movements.findAdjustment(skuId, key);
        if (recorded.isPresent()) {
            var saved = recorded.get();
            var original = saved.movement();
            if (original.delta() != delta || !original.reason().equals(reason) || saved.expectedVersion() != expectedVersion) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "La clave de idempotencia ya se usó con otros datos.");
            }
            return original;
        }
        if (expectedVersion != sku.getVersion()) {
            throw ListingService.staleVersion();
        }
        int before = sku.getAvailableStock();
        long after = (long) before + delta;
        if (after < 0) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                    "El ajuste dejaría el stock disponible por debajo de cero.");
        }
        if (after > Integer.MAX_VALUE) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "STOCK_LIMIT_EXCEEDED",
                    "El ajuste supera el máximo de stock disponible.");
        }

        sku.changeAvailableStock((int) after);
        skus.flush();
        // PostgreSQL persiste microsegundos. El primer resultado y su replay deben ser idénticos.
        var movement = new Movement(UUID.randomUUID(), skuId, delta, reason, before, (int) after, actor,
                traceId, sku.getVersion(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        movements.insert(movement, key, expectedVersion);
        return movement;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MovementPage history(UUID actor, UUID skuId, int page, int size) {
        requireOwner(actor, skuId);
        return movements.page(skuId, page, size);
    }

    private void requireOwner(UUID actor, UUID skuId) {
        if (!skus.existsOwnedSku(skuId, listings.sellerId(actor))) {
            throw missingSku();
        }
    }

    private static ApiProblemException missingSku() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND", "No encontramos esa variante.");
    }
}
