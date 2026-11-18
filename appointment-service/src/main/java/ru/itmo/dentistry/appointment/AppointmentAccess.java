package ru.itmo.dentistry.appointment;

import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

record AppointmentAccess(UUID userId, Scope scope) {

    enum Scope { PATIENT, DOCTOR, STAFF }

    static AppointmentAccess from(Jwt jwt) {
        Scope scope = switch (jwt.getClaimAsString("role")) {
            case "PATIENT" -> Scope.PATIENT;
            case "DOCTOR" -> Scope.DOCTOR;
            case "RECEPTIONIST", "ADMIN", "SUPERVISOR" -> Scope.STAFF;
            default -> throw new AccessDeniedException("Unsupported appointment role");
        };
        return new AppointmentAccess(UUID.fromString(jwt.getSubject()), scope);
    }

    void checkBooking(UUID patientId) {
        if (scope == Scope.PATIENT && !userId.equals(patientId)) {
            throw new AccessDeniedException("A patient can book appointments only for themselves");
        }
    }

    void checkAvailability(UUID doctorId) {
        if (scope == Scope.DOCTOR && !userId.equals(doctorId)) {
            throw new AccessDeniedException("A doctor can view only their own availability");
        }
    }

    void check(Appointment appointment) {
        boolean allowed = switch (scope) {
            case PATIENT -> userId.equals(appointment.getPatientId());
            case DOCTOR -> userId.equals(appointment.getDoctorId());
            case STAFF -> true;
        };
        if (!allowed) {
            throw new AccessDeniedException("You can access only your own appointments");
        }
    }

    Specification<Appointment> visibleAppointments() {
        return switch (scope) {
            case PATIENT -> (root, query, cb) -> cb.equal(root.get("patientId"), userId);
            case DOCTOR -> (root, query, cb) -> cb.equal(root.get("doctorId"), userId);
            case STAFF -> Specification.unrestricted();
        };
    }
}
