package com.hotel.dto;

import java.time.Instant;

/** Staff session: send {@code token} back as {@code X-Staff-Token} until {@code expiresAt}. */
public record StaffLoginResponse(String token, Instant expiresAt, long expiresInSeconds) {}
