package ru.itmo.dentistry.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import ru.itmo.dentistry.catalog.auth.*;
import ru.itmo.dentistry.catalog.common.*;
import ru.itmo.dentistry.catalog.config.*;
import ru.itmo.dentistry.catalog.internal.*;
import ru.itmo.dentistry.catalog.procedure.*;
import ru.itmo.dentistry.catalog.user.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("HTTP API каталога с настоящей проверкой JWT и ролей")
class CatalogHttpTest {
    private static final UUID ID = UUID.randomUUID();
    private AnnotationConfigApplicationContext context;
    private WebTestClient client;
    private UserService users;
    private ProcedureService procedures;
    private AuthService auth;
    private JwtTokenService tokens;

    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    @Import({SecurityConfiguration.class, JwtEncoderConfiguration.class, PasswordConfiguration.class,
            OpenApiConfiguration.class, UserController.class, ProcedureController.class,
            AuthController.class, InternalCatalogController.class, GlobalExceptionHandler.class})
    static class ApiConfiguration { }

    @BeforeAll
    void start() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "dentistry.security.jwt-secret", "catalog-test-signing-key-at-least-32-bytes",
                "dentistry.security.internal-api-token", "internal-test-token")));
        users = mock(UserService.class);
        procedures = mock(ProcedureService.class);
        auth = mock(AuthService.class);
        context.registerBean(UserService.class, () -> users);
        context.registerBean(ProcedureService.class, () -> procedures);
        context.registerBean(AuthService.class, () -> auth);
        context.register(ApiConfiguration.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context).build();
        tokens = new JwtTokenService(context.getBean(JwtEncoder.class), 900);
        assertEquals("bearer", context.getBean(io.swagger.v3.oas.models.OpenAPI.class)
                .getComponents().getSecuritySchemes().get("bearerAuth").getScheme());
    }

    @BeforeEach
    void resetServices() {
        reset(users, procedures, auth);
    }

    @AfterAll
    void close() {
        context.close();
    }

    private String token(String role) {
        return tokens.issue(ID, "test@example.com", role).accessToken();
    }

    private UserResponse profile() {
        return new UserResponse(ID, "Patient", "+79990000000", "test@example.com",
                UserRole.PATIENT, true, Set.of(), null, null, Set.of());
    }

    @Test
    @DisplayName("Без JWT и с повреждённой подписью защищённые запросы отклоняются")
    void authenticationIsRequired() {
        client.get().uri("/api/v1/users").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/users").headers(h -> h.setBearerAuth("broken.jwt"))
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(users);
    }

    @ParameterizedTest(name = "Создание пользователя с ролью {0}")
    @ValueSource(strings = {"SUPERVISOR", "ADMIN", "PATIENT", "DOCTOR", "RECEPTIONIST"})
    @DisplayName("Создавать пользователей может только супервайзер")
    void onlySupervisorCanCreate(String role) {
        when(users.create(any())).thenReturn(Mono.just(profile()));
        var response = client.post().uri("/api/v1/users").headers(h -> h.setBearerAuth(token(role)))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "fullName", "Patient", "email", "test@example.com", "phone", "+79990000000",
                        "password", "Password123!", "role", "PATIENT")).exchange();
        if (role.equals("SUPERVISOR")) {
            response.expectStatus().isCreated().expectBody().jsonPath("$.password").doesNotExist()
                    .jsonPath("$.passwordHash").doesNotExist().jsonPath("$.role").isEqualTo("PATIENT");
            verify(users).create(any());
        } else {
            response.expectStatus().isForbidden();
            verifyNoInteractions(users);
        }
    }

    @Test
    @DisplayName("Вход возвращает JWT, а /me читает UUID и роль из токена")
    void loginAndIdentity() {
        var login = tokens.issue(ID, "test@example.com", "SUPERVISOR");
        when(auth.login(any())).thenReturn(Mono.just(login));
        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "test@example.com", "password", "Password123!"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.accessToken").isEqualTo(login.accessToken())
                .jsonPath("$.expiresIn").isEqualTo(900)
                .jsonPath("$.user.password").doesNotExist();
        client.get().uri("/api/v1/auth/me").headers(h -> h.setBearerAuth(login.accessToken()))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.id").isEqualTo(ID.toString()).jsonPath("$.role").isEqualTo("SUPERVISOR");
    }

    @Test
    @DisplayName("Супервайзер читает, обновляет и отключает пользователей и назначает процедуры")
    void userManagement() {
        String token = token("SUPERVISOR");
        when(users.findById(ID)).thenReturn(Mono.just(profile()));
        when(users.findAll(anyInt(), anyInt(), any())).thenReturn(Mono.just(
                new org.springframework.data.domain.PageImpl<>(List.of(profile()),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1)));
        when(users.update(eq(ID), any())).thenReturn(Mono.just(profile()));
        when(users.updatePassword(eq(ID), any())).thenReturn(Mono.empty());
        when(users.deactivate(ID)).thenReturn(Mono.empty());
        when(users.deletePermanently(ID)).thenReturn(Mono.empty());
        when(users.assignProcedure(ID, ID)).thenReturn(Mono.just(profile()));
        when(users.removeProcedure(ID, ID)).thenReturn(Mono.just(profile()));
        client.get().uri("/api/v1/users/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/users?role=PATIENT").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
        client.put().uri("/api/v1/users/" + ID).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("fullName", "Patient",
                        "phone", "+79990000000", "email", "test@example.com", "role", "PATIENT"))
                .exchange().expectStatus().isOk();
        client.put().uri("/api/v1/users/" + ID + "/password").headers(h -> h.setBearerAuth(token))
                .bodyValue(Map.of("password", "Password123!"))
                .exchange().expectStatus().isNoContent();
        client.put().uri("/api/v1/users/" + ID + "/procedures/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
        client.delete().uri("/api/v1/users/" + ID + "/procedures/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
        client.delete().uri("/api/v1/users/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNoContent();
        client.delete().uri("/api/v1/users/" + ID + "/permanent").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNoContent();
    }

    @Test
    @DisplayName("Каталог процедур поддерживает CRUD и возвращает общее число в заголовке")
    void procedureManagement() {
        var procedure = new DentalProcedureResponse(ID, "Cleaning", null, BigDecimal.TEN, 30, true);
        when(procedures.create(any())).thenReturn(Mono.just(procedure));
        when(procedures.update(eq(ID), any())).thenReturn(Mono.just(procedure));
        when(procedures.findById(ID)).thenReturn(Mono.just(procedure));
        when(procedures.findAll(0, 20)).thenReturn(Mono.just(
                new ProcedureService.ProcedurePage(List.of(procedure), 1)));
        when(procedures.deactivate(ID)).thenReturn(Mono.empty());
        var request = new DentalProcedureRequest("Cleaning", null, BigDecimal.TEN, 30);
        String token = token("ADMIN");
        client.post().uri("/api/v1/procedures").headers(h -> h.setBearerAuth(token)).bodyValue(request)
                .exchange().expectStatus().isCreated();
        client.put().uri("/api/v1/procedures/" + ID).headers(h -> h.setBearerAuth(token)).bodyValue(request)
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/procedures/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/procedures").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Total-Count", "1");
        client.delete().uri("/api/v1/procedures/" + ID).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNoContent();
    }

    @Test
    @DisplayName("Внутренние данные доступны только с верным межсервисным токеном")
    void internalApi() {
        when(users.findInternalById(ID)).thenReturn(Mono.just(new CatalogUserView(
                ID, "Doctor", "DOCTOR", true, null, null, Set.of())));
        when(procedures.findById(ID)).thenReturn(Mono.just(
                new DentalProcedureResponse(ID, "Cleaning", null, BigDecimal.TEN, 30, true)));
        client.get().uri("/internal/users/" + ID).exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/users/" + ID).header("X-Internal-Token", "wrong")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/users/" + ID).header("X-Internal-Token", "internal-test-token")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.passwordHash").doesNotExist();
        client.get().uri("/internal/procedures/" + ID).header("X-Internal-Token", "internal-test-token")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.name").isEqualTo("Cleaning");
    }

    @Test
    @DisplayName("Ошибки поиска, конфликта, бизнес-правил и JSON возвращаются в формате API")
    void errorsHaveConsistentStatuses() {
        var errors = List.of(new NotFoundException("missing"), new ConflictException("duplicate"),
                new DataIntegrityViolationException("db"), new BusinessValidationException("invalid"));
        int[] statuses = {404, 409, 409, 400};
        for (int i = 0; i < errors.size(); i++) {
            when(users.findById(ID)).thenReturn(Mono.error(errors.get(i)));
            client.get().uri("/api/v1/users/" + ID).headers(h -> h.setBearerAuth(token("SUPERVISOR")))
                    .exchange().expectStatus().isEqualTo(statuses[i]).expectBody()
                    .jsonPath("$.path").isEqualTo("/api/v1/users/" + ID);
        }
        client.post().uri("/api/v1/users").headers(h -> h.setBearerAuth(token("SUPERVISOR")))
                .bodyValue(Map.of("fullName", "")).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.fieldErrors.email").exists();
        client.get().uri("/api/v1/users/not-a-uuid").headers(h -> h.setBearerAuth(token("SUPERVISOR")))
                .exchange().expectStatus().isBadRequest();
    }

    @ParameterizedTest(name = "Недопустимый JWT — {0}")
    @ValueSource(strings = {"expired", "issuer", "subject", "missing-subject"})
    @DisplayName("Подписанные токены проверяются по времени, издателю и формату UUID")
    void invalidClaims(String kind) {
        var claims = JwtClaimsSet.builder().issuer(kind.equals("issuer") ? "foreign" : "highload-dentistry")
                .issuedAt(Instant.now().minusSeconds(1000))
                .expiresAt(kind.equals("expired") ? Instant.now().minusSeconds(300) : Instant.now().plusSeconds(300))
                .claim("role", "SUPERVISOR");
        if (!kind.equals("missing-subject")) {
            claims.subject(kind.equals("subject") ? "invalid-uuid" : ID.toString());
        }
        String token = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        client.get().uri("/api/v1/users").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("BCrypt использует соль, проверяет пароль, а DTO исключают секреты при сериализации")
    void protectsPasswords() {
        var encoder = context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
        String first = encoder.encode("Password123!");
        String second = encoder.encode("Password123!");
        assertNotEquals(first, second);
        assertTrue(encoder.matches("Password123!", first));
        assertFalse(encoder.matches("wrong", first));
        assertTrue(first.startsWith("$2a$12$"));
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var user = UserAccount.create();
        user.setPasswordHash(first);
        assertFalse(mapper.writeValueAsString(user).contains("passwordHash"));
        var login = new LoginRequest("test@example.com", "Password123!");
        assertFalse(login.toString().contains("Password123!"));
        assertFalse(mapper.writeValueAsString(login).contains("Password123!"));
        var update = new UpdatePasswordRequest("Password123!");
        assertFalse(update.toString().contains("Password123!"));
        assertFalse(mapper.writeValueAsString(update).contains("Password123!"));
        assertTrue(user.isNew());
        user.markPersisted();
        assertFalse(user.isNew());
    }
}

