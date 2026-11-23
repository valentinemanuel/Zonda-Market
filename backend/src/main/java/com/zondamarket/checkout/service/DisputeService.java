package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.DisputeDtos.*;
import com.zondamarket.checkout.dto.FulfillmentDtos.ShipmentResponse;
import com.zondamarket.checkout.repository.DisputeStore;
import com.zondamarket.checkout.repository.DisputeStore.Case;
import com.zondamarket.checkout.repository.FulfillmentStore;
import com.zondamarket.identity.domain.UserAccount.Role;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisputeService {
    private final DisputeStore store;
    private final FulfillmentStore fulfillment;
    private final Clock clock;

    DisputeService(DisputeStore store, FulfillmentStore fulfillment, Clock clock) {
        this.store = store;
        this.fulfillment = fulfillment;
        this.clock = clock;
    }

    @Transactional
    public Reference open(UUID buyer, UUID subOrder, OpenRequest request, UUID trace) {
        var order = fulfillment.lockParent(subOrder, buyer, false).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "SUB_ORDER_NOT_FOUND", "No encontramos esa suborden."));
        var sub = fulfillment.lockSubOrder(subOrder);
        String reason = plainText(request.reason());
        String description = plainText(request.description());
        var existing = store.forSubOrder(subOrder).orElse(null);
        if (existing != null) {
            if (existing.summary().reason().equals(reason) && existing.description().equals(description)) {
                return reference(existing);
            }
            throw conflict("DISPUTE_ALREADY_EXISTS", "Esta suborden ya tiene un reclamo. Consultá su historial.");
        }
        if (request.expectedVersion() != null && request.expectedVersion().intValueExact() != sub.version()) {
            throw conflict("SUB_ORDER_VERSION_CONFLICT", "La suborden cambió. Consultá su estado antes de abrir el reclamo.");
        }
        var now = now();
        if (!("PAGADA".equals(order.status()) || "REEMBOLSO_PARCIAL".equals(order.status())) || !eligible(sub, now)) {
            throw conflict("DISPUTE_INELIGIBLE", "El estado o el plazo de esta suborden no permite abrir un reclamo.");
        }
        UUID id = UUID.randomUUID();
        store.open(id, subOrder, reason, description, now);
        fulfillment.event(order.id(), subOrder, "DISPUTE_OPENED", buyer, trace, now);
        return new Reference(id, Status.ABIERTA);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(AccountResponse actor, UUID id, int page, int size) {
        var dispute = requireCase(actor, id, false);
        var summary = dispute.summary();
        var messages = store.messages(id, page, size);
        return new Detail(id, dispute.orderId(), summary.subOrderId(), summary.status(), summary.reason(),
                dispute.description(), summary.openedAt(), summary.assignedAt(), dispute.participants(), participant(dispute, actor),
                messages.subList(0, Math.min(size, messages.size())), page, size, messages.size() > size);
    }

    @Transactional
    public Message message(AccountResponse actor, UUID id, MessageRequest request, UUID key) {
        var dispute = requireCase(actor, id, true);
        String text = plainText(request.body());
        var replay = key == null ? null : store.replay(id, actor.id(), key).orElse(null);
        if (replay != null) {
            if (!replay.body().equals(text)) throw conflict("IDEMPOTENCY_KEY_REUSED", "Esa clave ya corresponde a otro mensaje.");
            return replay;
        }
        if (!dispute.summary().status().active()) throw conflict("DISPUTE_CLOSED", "El reclamo está cerrado.");
        return store.message(id, actor.id(), participant(dispute, actor), text, key, now());
    }

    @Transactional(readOnly = true)
    public CasePage queue(Status status, Integer maxAgeDays, int page, int size) {
        var since = maxAgeDays == null ? null : now().minus(maxAgeDays, ChronoUnit.DAYS);
        var cases = store.queue(status, since, page, size);
        return new CasePage(cases.subList(0, Math.min(size, cases.size())), page, size, cases.size() > size);
    }

    @Transactional
    public Reference assign(AccountResponse admin, UUID id, UUID trace) {
        var dispute = requireCase(admin, id, true);
        if (dispute.summary().status() == Status.EN_MEDIACION && admin.id().equals(dispute.participants().mediatorId())) {
            return reference(dispute);
        }
        if (dispute.summary().status() != Status.ABIERTA) {
            throw conflict("DISPUTE_ASSIGNMENT_CONFLICT", "El reclamo ya cambió de estado o tiene un mediador.");
        }
        var now = now();
        store.assign(id, admin.id(), now);
        fulfillment.event(dispute.orderId(), dispute.summary().subOrderId(), "DISPUTE_ASSIGNED", admin.id(), trace, now);
        return new Reference(id, Status.EN_MEDIACION);
    }

    private Case requireCase(AccountResponse actor, UUID id, boolean lock) {
        return store.visible(id, actor.id(), actor.roles().contains(Role.ADMIN), lock)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "DISPUTE_NOT_FOUND", "No encontramos ese reclamo."));
    }

    private static ParticipantRole participant(Case dispute, AccountResponse actor) {
        if (actor.roles().contains(Role.ADMIN)) return ParticipantRole.ADMIN;
        return actor.id().equals(dispute.participants().buyerId()) ? ParticipantRole.BUYER : ParticipantRole.SELLER;
    }

    private static boolean eligible(ShipmentResponse sub, Instant now) {
        return switch (sub.status()) {
            case "PREPARANDO", "DESPACHADA" -> true;
            case "ENTREGADA" -> sub.shipment() != null && sub.shipment().deliveredAt() != null
                    && !now.isAfter(sub.shipment().deliveredAt().plus(7, ChronoUnit.DAYS));
            default -> false;
        };
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static Reference reference(Case dispute) {
        return new Reference(dispute.summary().id(), dispute.summary().status());
    }

    private static String plainText(String raw) {
        String text = raw.strip();
        if (text.isEmpty() || text.indexOf(0) >= 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Ingresá texto válido.");
        }
        return text;
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
