package com.zondamarket.admin.service;

import com.zondamarket.admin.dto.ModerationDtos.*;
import com.zondamarket.admin.repository.ModerationStore;
import com.zondamarket.admin.repository.ModerationStore.Target;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.repository.RefreshSessionRepository;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationService {
    private final ModerationStore store;
    private final RefreshSessionRepository sessions;
    private final Clock clock;

    ModerationService(ModerationStore store, RefreshSessionRepository sessions, Clock clock) {
        this.store = store;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ListingRow> listings(String q, String state, int page, int size) {
        return Page.from(store.listings(q.strip(), state, (long) page * size, size + 1), page, size);
    }

    @Transactional(readOnly = true)
    public Page<AccountRow> accounts(String q, String state, int page, int size) {
        return Page.from(store.accounts(q.strip(), state, (long) page * size, size + 1), page, size);
    }

    @Transactional(readOnly = true)
    public Page<Audit> history(Target target, UUID id, int page, int size) {
        return Page.from(store.history(target, id, (long) page * size, size + 1), page, size);
    }

    @Transactional(readOnly = true)
    public Page<OrderCase> cases(Target target, UUID id, int page, int size) {
        return Page.from(store.cases(target, id, (long) page * size, size + 1), page, size);
    }

    @Transactional
    public Audit moderate(UUID id, Command<ListingAction> command, AccountResponse actor, UUID trace) {
        var listing = store.lockListing(id).orElseThrow(ModerationService::missing);
        boolean hidden = command.action() == ListingAction.HIDE;
        String reason = reason(command.reason());
        requireChange(listing.version(), command.expectedVersion(), listing.hidden() == hidden);
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        store.setListingHidden(id, hidden, now);
        return store.record(Target.LISTING, id, actor.id(), actor.email(), command.action().name(), reason,
                hidden ? State.UNHIDDEN : State.HIDDEN, hidden ? State.HIDDEN : State.UNHIDDEN,
                listing.version() + 1, trace, now);
    }

    @Transactional
    public Audit changeStatus(UUID id, Command<AccountAction> command, AccountResponse actor, UUID trace) {
        var account = store.lockAccount(id).orElseThrow(ModerationService::missing);
        if (id.equals(actor.id()) || "ADMIN".equals(account.role())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "ADMIN_STATUS_PROTECTED",
                    "Las cuentas administradoras no se pueden modificar desde este panel.");
        }
        boolean suspended = command.action() == AccountAction.SUSPEND;
        String reason = reason(command.reason());
        requireChange(account.version(), command.expectedVersion(), account.suspended() == suspended);
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        store.setAccountSuspended(id, suspended, now);
        if (suspended) {
            sessions.revokeAllForAccount(id, now.toInstant());
        }
        return store.record(Target.ACCOUNT, id, actor.id(), actor.email(), command.action().name(), reason,
                suspended ? State.ACTIVE : State.SUSPENDED, suspended ? State.SUSPENDED : State.ACTIVE,
                account.version() + 1, trace, now);
    }

    private static void requireChange(long version, long expectedVersion, boolean unchanged) {
        if (version != expectedVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "El registro cambió. Revisá el estado actual antes de volver a confirmar.");
        }
        if (unchanged) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "MODERATION_STATE_UNCHANGED",
                    "El registro ya se encuentra en ese estado.");
        }
    }

    private static String reason(String raw) {
        String value = raw.strip();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Escribí un motivo en texto plano.");
        }
        return value;
    }

    private static ApiProblemException missing() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "MODERATION_TARGET_NOT_FOUND", "No encontramos ese registro.");
    }
}
