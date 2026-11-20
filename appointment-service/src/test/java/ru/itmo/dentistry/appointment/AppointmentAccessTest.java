package ru.itmo.dentistry.appointment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("Проверка владельца приёма")
class AppointmentAccessTest {
    @ParameterizedTest(name = "Область доступа для роли {0}")
    @ValueSource(strings = {"PATIENT", "DOCTOR", "ADMIN", "RECEPTIONIST", "SUPERVISOR"})
    @DisplayName("Каждая роль получает соответствующую область доступа")
    void mapsRolesAndChecksOwnership(String role) {
        UUID id = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject(id.toString()).claim("role", role).build();
        var access = AppointmentAccess.from(jwt);
        var appointment = new Appointment();
        appointment.setPatientId(id);
        appointment.setDoctorId(id);
        assertDoesNotThrow(() -> access.check(appointment));
        access.checkBooking(id);
        access.checkAvailability(id);
        if (role.equals("DOCTOR")) {
            appointment.setDoctorId(UUID.randomUUID());
            assertThrows(AccessDeniedException.class, () -> access.check(appointment));
            assertThrows(AccessDeniedException.class, () -> access.checkAvailability(UUID.randomUUID()));
        }
        if (role.equals("PATIENT")) {
            appointment.setPatientId(UUID.randomUUID());
            assertThrows(AccessDeniedException.class, () -> access.check(appointment));
            assertThrows(AccessDeniedException.class, () -> access.checkBooking(UUID.randomUUID()));
        }
    }

    @Test
    @DisplayName("Неизвестная роль не получает доступ к приёмам")
    void rejectsUnknownRole() {
        var jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim("role", "UNKNOWN").build();
        assertThrows(AccessDeniedException.class, () -> AppointmentAccess.from(jwt));
    }
}

