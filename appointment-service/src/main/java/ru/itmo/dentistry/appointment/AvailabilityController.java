package ru.itmo.dentistry.appointment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/{doctorId}/availability")
@Tag(name = "Availability", description = "Available doctor time intervals")
public class AvailabilityController {

    private final AppointmentService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'RECEPTIONIST', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Get available slots for a doctor and date")
    public Mono<ResponseEntity<List<AvailabilityResponse>>> availability(
            @PathVariable UUID doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal Jwt jwt) {
        if ("DOCTOR".equals(jwt.getClaimAsString("role")) && !doctorId.toString().equals(jwt.getSubject())) {
            return Mono.error(new AccessDeniedException("A doctor can view only their own availability"));
        }
        return Mono.fromCallable(() -> ResponseEntity.ok(service.availability(doctorId, date)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
