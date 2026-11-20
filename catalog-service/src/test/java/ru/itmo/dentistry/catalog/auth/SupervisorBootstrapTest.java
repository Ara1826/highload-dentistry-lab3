package ru.itmo.dentistry.catalog.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import ru.itmo.dentistry.catalog.user.*;
import org.springframework.security.crypto.password.PasswordEncoder;

@DisplayName("Создание первого супервайзера")
class SupervisorBootstrapTest {
    private UserRepository users;
    private PasswordEncoder encoder;
    private SupervisorBootstrap bootstrap;

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        bootstrap = new SupervisorBootstrap(users, encoder);
        ReflectionTestUtils.setField(bootstrap, "email", " SUPERVISOR@example.com ");
        ReflectionTestUtils.setField(bootstrap, "password", "LongPassword123!");
        ReflectionTestUtils.setField(bootstrap, "fullName", "Supervisor");
        ReflectionTestUtils.setField(bootstrap, "phone", "+79990000000");
        when(encoder.encode(any())).thenReturn("bcrypt-hash");
    }

    @Test
    @DisplayName("Первый запуск создаёт активного супервайзера с хешем и нормализованным email")
    void createsSupervisor() {
        when(users.findByEmailIgnoreCase("supervisor@example.com")).thenReturn(Mono.empty());
        when(users.save(any())).thenAnswer(call -> {
            UserAccount user = call.getArgument(0);
            assertEquals("SUPERVISOR", user.getRole());
            assertEquals("supervisor@example.com", user.getEmail());
            assertEquals("bcrypt-hash", user.getPasswordHash());
            assertTrue(user.isActive());
            return Mono.just(user);
        });
        bootstrap.run(null);
        verify(users).save(any());
    }

    @ParameterizedTest(name = "Существующий аккаунт — {0}")
    @ValueSource(strings = {"password-present", "inactive", "password-missing", "different-role"})
    @DisplayName("Повторный запуск сохраняет существующие права и состояние аккаунта")
    void handlesExistingAccount(String state) {
        var user = UserAccount.create();
        user.setRole(state.equals("different-role") ? "PATIENT" : "SUPERVISOR");
        user.setActive(!state.equals("inactive"));
        user.setPasswordHash(state.equals("password-present") ? "existing-hash" : null);
        when(users.findByEmailIgnoreCase("supervisor@example.com")).thenReturn(Mono.just(user));
        if (state.equals("password-missing")) {
            when(users.initializeSupervisorPassword(user.getId(), "bcrypt-hash")).thenReturn(Mono.just(1));
        }
        if (state.equals("different-role")) {
            assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
            assertEquals("PATIENT", user.getRole());
        } else {
            bootstrap.run(null);
        }
        verify(users, never()).save(any());
        assertEquals(!state.equals("inactive"), user.isActive());
    }

    @Test
    @DisplayName("Одновременный запуск двух экземпляров обрабатывает уже созданного супервайзера")
    void handlesConcurrentCreation() {
        var user = UserAccount.create();
        user.setRole("SUPERVISOR");
        user.setPasswordHash("existing-hash");
        when(users.findByEmailIgnoreCase("supervisor@example.com")).thenReturn(Mono.empty(), Mono.just(user));
        when(users.save(any())).thenReturn(Mono.error(new DataIntegrityViolationException("race")));
        bootstrap.run(null);
        verify(users, times(2)).findByEmailIgnoreCase("supervisor@example.com");
    }

    @Test
    @DisplayName("Пустые настройки отключают bootstrap, а некорректный пароль отклоняется")
    void validatesConfiguration() {
        ReflectionTestUtils.setField(bootstrap, "email", "");
        ReflectionTestUtils.setField(bootstrap, "password", "");
        bootstrap.run(null);
        verifyNoInteractions(users);
        ReflectionTestUtils.setField(bootstrap, "email", "supervisor@example.com");
        ReflectionTestUtils.setField(bootstrap, "password", "short");
        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        ReflectionTestUtils.setField(bootstrap, "password", "я".repeat(37));
        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
    }
}

