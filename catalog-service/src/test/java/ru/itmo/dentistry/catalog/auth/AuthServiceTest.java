package ru.itmo.dentistry.catalog.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.itmo.dentistry.catalog.user.UserAccount;
import ru.itmo.dentistry.catalog.user.UserRepository;
import ru.itmo.dentistry.catalog.user.UserRole;

@DisplayName("Вход в систему")
class AuthServiceTest {
    private UserRepository users;
    private PasswordEncoder passwords;
    private JwtTokenService tokens;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        passwords = mock(PasswordEncoder.class);
        tokens = mock(JwtTokenService.class);
        service = new AuthService(users, passwords, tokens);
    }

    @Test
    @DisplayName("Активный пользователь с верным паролем получает токен")
    void successfulLoginIssuesTokenForActiveUser() {
        UserAccount user = account();
        when(users.findByEmailIgnoreCase("doctor@example.com")).thenReturn(Mono.just(user));
        when(passwords.matches("StrongPassword123!", "bcrypt-hash")).thenReturn(true);
        var expected = new LoginResponse("signed.jwt", "Bearer", 3600,
                new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()));
        when(tokens.issue(user.getId(), user.getEmail(), user.getRole())).thenReturn(expected);

        StepVerifier.create(service.login(new LoginRequest(" DOCTOR@example.com ", "StrongPassword123!")))
                .expectNext(expected)
                .verifyComplete();
        verify(passwords).matches("StrongPassword123!", "bcrypt-hash");
    }

    @Test
    @DisplayName("Неверный пароль отклоняется с кодом 401")
    void invalidPasswordIsRejected() {
        UserAccount user = account();
        when(users.findByEmailIgnoreCase("doctor@example.com")).thenReturn(Mono.just(user));
        when(passwords.matches("wrong", "bcrypt-hash")).thenReturn(false);

        StepVerifier.create(service.login(new LoginRequest("doctor@example.com", "wrong")))
                .expectErrorMatches(error -> error instanceof ResponseStatusException ex
                        && ex.getStatusCode() == HttpStatus.UNAUTHORIZED)
                .verify();
    }

    @Test
    @DisplayName("Неактивный пользователь не может войти")
    void inactiveUserCannotLogin() {
        UserAccount user = account();
        user.setActive(false);
        when(users.findByEmailIgnoreCase("doctor@example.com")).thenReturn(Mono.just(user));

        StepVerifier.create(service.login(new LoginRequest("doctor@example.com", "StrongPassword123!")))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    @DisplayName("Пароль длиннее 72 байт UTF-8 отклоняется до проверки BCrypt")
    void rejectsPasswordsBeyondBCryptByteLimit() {
        StepVerifier.create(service.login(new LoginRequest("doctor@example.com", "я".repeat(37))))
                .expectErrorMatches(error -> error instanceof ResponseStatusException ex
                        && ex.getStatusCode() == HttpStatus.UNAUTHORIZED)
                .verify();
        verifyNoInteractions(users, passwords, tokens);
    }

    private UserAccount account() {
        UserAccount user = UserAccount.create();
        user.setFullName("Doctor Example");
        user.setPhone("+79990000001");
        user.setEmail("doctor@example.com");
        user.setRole(UserRole.DOCTOR.name());
        user.setPasswordHash("bcrypt-hash");
        return user;
    }
}
