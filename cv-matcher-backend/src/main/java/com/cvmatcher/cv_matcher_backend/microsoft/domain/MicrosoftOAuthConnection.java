package com.cvmatcher.cv_matcher_backend.microsoft.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "microsoft_oauth_connection")
public class MicrosoftOAuthConnection {

    @Id
    private UUID id;

    @Column(name = "refresh_token_ciphertext", nullable = false)
    private byte[] refreshTokenCiphertext;

    @Column(name = "refresh_token_nonce", nullable = false)
    private byte[] refreshTokenNonce;

    @Column(name = "encryption_key_version", nullable = false, length = 32)
    private String encryptionKeyVersion;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected MicrosoftOAuthConnection() {}

    public MicrosoftOAuthConnection(byte[] refreshTokenCiphertext, byte[] refreshTokenNonce, String encryptionKeyVersion) {
        this.id = UUID.randomUUID();
        this.refreshTokenCiphertext = refreshTokenCiphertext;
        this.refreshTokenNonce = refreshTokenNonce;
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.active = true;
        this.connectedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public boolean isActive() { return active; }
    public Instant getConnectedAt() { return connectedAt; }
    public byte[] getRefreshTokenCiphertext() { return refreshTokenCiphertext; }
    public byte[] getRefreshTokenNonce() { return refreshTokenNonce; }

    public void revoke() {
        this.active = false;
        this.revokedAt = Instant.now();
    }
}
