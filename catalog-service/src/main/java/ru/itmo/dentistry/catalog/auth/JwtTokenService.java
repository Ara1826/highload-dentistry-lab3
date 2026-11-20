package ru.itmo.dentistry.catalog.auth;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class  JwtTokenService {
    private final JwtEncoder encoder;
    @Value("${dentistry.security.jwt-ttl-seconds:900}")
    private final long ttlSeconds;

    public LoginResponse issue(UUID id, String email, String role) {
        Instant now = Instant.now();
        var headers = JwsHeader.with(MacAlgorithm.HS256).build();
        var claims = JwtClaimsSet.builder()
                .issuer("highload-dentistry")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .subject(id.toString())
                .claim("email", email)
                .claim("role", role)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", ttlSeconds, new AuthenticatedUser(id, email, role));
    }
}
