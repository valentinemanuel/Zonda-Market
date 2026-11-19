package com.zondamarket.identity.repository;

import com.zondamarket.identity.service.GoogleOidcClient.Identity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GoogleIdentityStore {
    public record Target(UUID account, UUID session) {}
    public record Attempt(String nonce, String verifier, Target target) {}
    public record Grant(Identity identity, Target target) {}

    private final JdbcClient db;

    GoogleIdentityStore(JdbcClient db) {
        this.db = db;
    }

    public void addAttempt(String stateHash, String browserHash, Attempt attempt, Instant expires) {
        db.sql("""
                INSERT INTO zonda_market.oauth_attempt
                    (state_hash, browser_hash, nonce, verifier, account_id, session_id, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(stateHash).param(browserHash).param(attempt.nonce()).param(attempt.verifier())
                .param(attempt.target().account()).param(attempt.target().session())
                .param(expires.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<Attempt> takeAttempt(String stateHash, String browserHash, Instant now) {
        return db.sql("""
                DELETE FROM zonda_market.oauth_attempt
                WHERE state_hash = ? AND browser_hash = ? AND expires_at > ?
                RETURNING nonce, verifier, account_id, session_id
                """).param(stateHash).param(browserHash).param(now.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> new Attempt(rs.getString("nonce"), rs.getString("verifier"), target(rs)))
                .optional();
    }

    public void addGrant(String codeHash, String browserHash, Grant grant, Instant expires) {
        Identity identity = grant.identity();
        db.sql("""
                INSERT INTO zonda_market.oauth_exchange
                    (code_hash, browser_hash, issuer, subject, email, account_id, session_id, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).param(codeHash).param(browserHash).param(identity.issuer()).param(identity.subject())
                .param(identity.email()).param(grant.target().account()).param(grant.target().session())
                .param(expires.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<Grant> takeGrant(String codeHash, String browserHash, Instant now) {
        return db.sql("""
                DELETE FROM zonda_market.oauth_exchange
                WHERE code_hash = ? AND browser_hash = ? AND expires_at > ?
                RETURNING issuer, subject, email, account_id, session_id
                """).param(codeHash).param(browserHash).param(now.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> new Grant(
                        new Identity(rs.getString("issuer"), rs.getString("subject"), rs.getString("email")), target(rs)))
                .optional();
    }

    public void deleteExpired(Instant now) {
        db.sql("DELETE FROM zonda_market.oauth_attempt WHERE expires_at <= ?")
                .param(now.atOffset(ZoneOffset.UTC)).update();
        db.sql("DELETE FROM zonda_market.oauth_exchange WHERE expires_at <= ?")
                .param(now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<UUID> findAccount(Identity identity) {
        return db.sql("""
                SELECT account_id FROM zonda_market.oauth_identity
                WHERE issuer = ? AND subject = ? AND NOT deleted
                """).param(identity.issuer()).param(identity.subject()).query(UUID.class).optional();
    }

    public Optional<UUID> addGoogleAccount(String email, boolean verified, Instant now) {
        return db.sql("""
                INSERT INTO zonda_market.user_account
                    (id, email, role, email_verified, created_at, updated_at, deleted)
                VALUES (?, ?, 'USER', ?, ?, ?, FALSE)
                ON CONFLICT (email) DO NOTHING
                RETURNING id
                """).param(UUID.randomUUID()).param(email).param(verified).param(now.atOffset(ZoneOffset.UTC))
                .param(now.atOffset(ZoneOffset.UTC)).query(UUID.class).optional();
    }

    public boolean addIdentity(UUID account, Identity identity, Instant now) {
        return db.sql("""
                INSERT INTO zonda_market.oauth_identity (id, account_id, issuer, subject, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """).param(UUID.randomUUID()).param(account).param(identity.issuer()).param(identity.subject())
                .param(now.atOffset(ZoneOffset.UTC)).param(now.atOffset(ZoneOffset.UTC)).update() == 1;
    }

    public boolean hasGoogle(UUID account) {
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM zonda_market.oauth_identity
                    WHERE account_id = ? AND NOT deleted)
                """).param(account).query(Boolean.class).single();
    }

    private static Target target(ResultSet rs) throws SQLException {
        return new Target(rs.getObject("account_id", UUID.class), rs.getObject("session_id", UUID.class));
    }
}
