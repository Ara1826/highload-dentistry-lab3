package ru.itmo.dentistry.appointment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import ru.itmo.dentistry.appointment.common.*;
import ru.itmo.dentistry.appointment.config.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("HTTP API приёмов с настоящей проверкой JWT")
class AppointmentHttpTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final Instant START = Instant.now().plusSeconds(86400);
    private AnnotationConfigApplicationContext context;
    private AppointmentService service;
    private WebTestClient client;
    private JwtEncoder encoder;

    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    @Import({SecurityConfiguration.class, OpenApiConfiguration.class, CatalogFeignConfiguration.class,
            AppointmentController.class, AvailabilityController.class, GlobalExceptionHandler.class})
    static class ApiConfiguration { }

    @BeforeAll
    void start() {
        String key = "appointment-test-signing-key-at-least-32-bytes";
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dentistry.security.jwt-secret", key,
                "dentistry.security.internal-api-token", "internal-token",
                "dentistry.appointment.zone-id", "Europe/Moscow",
                "dentistry.appointment.slot-minutes", "30")));
        service = mock(AppointmentService.class);
        context.registerBean(AppointmentService.class, () -> service);
        context.register(ApiConfiguration.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context).build();
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256")));
        var template = new feign.RequestTemplate();
        context.getBean(feign.RequestInterceptor.class).apply(template);
        assertEquals(List.of("internal-token"), List.copyOf(template.headers().get("X-Internal-Token")));
        assertNotNull(context.getBean(io.swagger.v3.oas.models.OpenAPI.class).getComponents());
    }

    @BeforeEach
    void resetService() {
        reset(service);
    }

    @AfterAll
    void close() {
        context.close();
    }

    private String token(String role) {
        return token(role, USER.toString(), "highload-dentistry", Instant.now().plusSeconds(900));
    }

    private String token(String role, String subject, String issuer, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(subject)
                .issuedAt(Instant.now().minusSeconds(1000)).expiresAt(expiry).claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private AppointmentResponse response() {
        return new AppointmentResponse(ID, USER, "Patient", DOCTOR, "Doctor", START, START.plusSeconds(1800),
                AppointmentStatus.SCHEDULED, null, List.of(), BigDecimal.ZERO, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("JWT обязателен, а неверные подпись, издатель, UUID и срок действия отклоняются")
    void validatesJwt() {
        client.get().uri("/api/v1/appointments").exchange().expectStatus().isUnauthorized();
        for (String token : List.of("broken", token("PATIENT", "wrong", "highload-dentistry", Instant.now().plusSeconds(900)),
                token("PATIENT", USER.toString(), "foreign", Instant.now().plusSeconds(900)),
                token("PATIENT", USER.toString(), "highload-dentistry", Instant.now().minusSeconds(300)))) {
            client.get().uri("/api/v1/appointments").headers(h -> h.setBearerAuth(token))
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    @ParameterizedTest(name = "Право завершать приём — {0}")
    @ValueSource(strings = {"SUPERVISOR", "ADMIN", "DOCTOR", "PATIENT", "RECEPTIONIST"})
    @DisplayName("Завершение лечения разрешено врачу и административным ролям")
    void completionRoleMatrix(String role) {
        when(service.complete(eq(ID), any())).thenReturn(response());
        var result = client.patch().uri("/api/v1/appointments/" + ID + "/complete")
                .headers(h -> h.setBearerAuth(token(role))).exchange();
        if (Set.of("SUPERVISOR", "ADMIN", "DOCTOR").contains(role)) {
            result.expectStatus().isOk();
            verify(service).complete(eq(ID), any());
        } else {
            result.expectStatus().isForbidden();
            verifyNoInteractions(service);
        }
    }

    @Test
    @DisplayName("Создание, чтение, прокрутка, перенос и отмена приёма доступны через HTTP")
    void appointmentLifecycle() {
        when(service.create(any(), any())).thenReturn(response());
        when(service.findById(eq(ID), any())).thenReturn(response());
        when(service.findAll(any(), anyInt(), any())).thenReturn(
                new AppointmentScrollResponse(List.of(response()), null, false));
        when(service.reschedule(eq(ID), any(), any())).thenReturn(response());
        String token = token("PATIENT");
        var request = new CreateAppointmentRequest(USER, DOCTOR, START, START.plusSeconds(1800), "Checkup");
        client.post().uri("/api/v1/appointments").headers(h -> h.setBearerAuth(token)).bodyValue(request)
                .exchange().expectStatus().isCreated().expectHeader()
                .valueEquals("Location", "/api/v1/appointments/" + ID);
        client.get().uri("/api/v1/appointments/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/appointments?cursor=test&size=1").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.hasNext").isEqualTo(false);
        client.patch().uri("/api/v1/appointments/" + ID + "/reschedule").headers(h -> h.setBearerAuth(token))
                .bodyValue(new RescheduleAppointmentRequest(START, START.plusSeconds(1800)))
                .exchange().expectStatus().isOk();
        client.delete().uri("/api/v1/appointments/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNoContent();
        verify(service).create(eq(request), eq(new AppointmentAccess(USER, AppointmentAccess.Scope.PATIENT)));
    }

    @Test
    @DisplayName("Врач управляет выполненными процедурами и видит собственные свободные окна")
    void doctorProceduresAndAvailability() {
        when(service.addProcedure(eq(ID), any(), any())).thenReturn(response());
        when(service.updateProcedure(eq(ID), eq(ID), any(), any())).thenReturn(response());
        when(service.availability(eq(USER), any())).thenReturn(List.of(
                new AvailabilityResponse(START, START.plusSeconds(1800))));
        String token = token("DOCTOR");
        client.post().uri("/api/v1/appointments/" + ID + "/procedures").headers(h -> h.setBearerAuth(token))
                .bodyValue(new AppointmentProcedureRequest(ID, 1, 11)).exchange().expectStatus().isOk();
        client.put().uri("/api/v1/appointments/" + ID + "/procedures/" + ID)
                .headers(h -> h.setBearerAuth(token)).bodyValue(new UpdateAppointmentProcedureRequest(2, 12))
                .exchange().expectStatus().isOk();
        client.delete().uri("/api/v1/appointments/" + ID + "/procedures/" + ID)
                .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isNoContent();
        client.get().uri("/api/v1/users/" + USER + "/availability?date=" + LocalDate.now())
                .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/users/" + DOCTOR + "/availability?date=" + LocalDate.now())
                .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Ошибки приёмов возвращаются с корректными статусами и описанием")
    void errorResponses() {
        List<RuntimeException> errors = List.of(new NotFoundException("missing"),
                new ConflictException("overlap"), new org.springframework.dao.DataIntegrityViolationException("db"),
                new BusinessValidationException("time"), new CatalogUnavailableException("catalog"));
        int[] statuses = {404, 409, 409, 400, 503};
        for (int i = 0; i < errors.size(); i++) {
            when(service.findById(eq(ID), any())).thenThrow(errors.get(i));
            client.get().uri("/api/v1/appointments/" + ID).headers(h -> h.setBearerAuth(token("PATIENT")))
                    .exchange().expectStatus().isEqualTo(statuses[i]).expectBody()
                    .jsonPath("$.path").isEqualTo("/api/v1/appointments/" + ID);
        }
        client.post().uri("/api/v1/appointments").headers(h -> h.setBearerAuth(token("PATIENT")))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.fieldErrors.patientId").exists();
        client.get().uri("/api/v1/appointments/invalid").headers(h -> h.setBearerAuth(token("PATIENT")))
                .exchange().expectStatus().isBadRequest();
    }
}

