package ru.itmo.dentistry.catalog.auth;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, AuthenticatedUser user) {
}
