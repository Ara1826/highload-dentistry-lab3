package ru.itmo.dentistry.gateway;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import ru.itmo.dentistry.gateway.config.SecurityConfiguration;

@DisplayName("Защита внешнего Gateway")
class GatewaySecurityTest {
    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    @Import({SecurityConfiguration.class, Probe.class})
    static class Api { }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/probe")
        String probe() { return "ok"; }
    }

    @Test
    @DisplayName("Gateway принимает действительный JWT и закрывает внутренние маршруты")
    void protectsRoutesAndValidatesClaims() {
        String key = "gateway-test-signing-key-at-least-32-bytes";
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("dentistry.security.jwt-secret", key)));
            context.register(Api.class);
            context.refresh();
            var client = WebTestClient.bindToApplicationContext(context).build();
            var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
            String valid = issue(encoder, UUID.randomUUID().toString(), "highload-dentistry", Instant.now().plusSeconds(900));
            client.get().uri("/api/v1/probe").exchange().expectStatus().isUnauthorized();
            client.get().uri("/api/v1/probe").headers(h -> h.setBearerAuth(valid))
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("ok");
            client.get().uri("/internal/users/1").headers(h -> h.setBearerAuth(valid))
                    .exchange().expectStatus().isForbidden();
            client.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
            for (String token : List.of("broken",
                    issue(encoder, "invalid", "highload-dentistry", Instant.now().plusSeconds(900)),
                    issue(encoder, null, "highload-dentistry", Instant.now().plusSeconds(900)),
                    issue(encoder, UUID.randomUUID().toString(), "foreign", Instant.now().plusSeconds(900)),
                    issue(encoder, UUID.randomUUID().toString(), "highload-dentistry", Instant.now().minusSeconds(300)))) {
                client.get().uri("/api/v1/probe").headers(h -> h.setBearerAuth(token))
                        .exchange().expectStatus().isUnauthorized();
            }
        }
    }

    private static String issue(JwtEncoder encoder, String subject, String issuer, Instant expiry) {
        var builder = JwtClaimsSet.builder().issuer(issuer)
                .issuedAt(Instant.now().minusSeconds(1000)).expiresAt(expiry);
        if (subject != null) {
            builder.subject(subject);
        }
        var claims = builder.build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}

