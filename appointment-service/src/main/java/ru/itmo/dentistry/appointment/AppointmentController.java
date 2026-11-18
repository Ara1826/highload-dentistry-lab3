package ru.itmo.dentistry.appointment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/appointments")
@Tag(name = "Appointments", description = "Appointment lifecycle and performed procedures")
public class AppointmentController {

    private final AppointmentService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('PATIENT', 'RECEPTIONIST', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Book an appointment")
    public Mono<ResponseEntity<AppointmentResponse>> create(
            @Valid @RequestBody CreateAppointmentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> {
            var created = service.create(request, AppointmentAccess.from(jwt));
            return ResponseEntity.created(URI.create("/api/v1/appointments/" + created.id())).body(created);
        });
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'RECEPTIONIST', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Get an appointment by id")
    public Mono<ResponseEntity<AppointmentResponse>> findById(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> ResponseEntity.ok(service.findById(id, AppointmentAccess.from(jwt))));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PATIENT', 'DOCTOR', 'RECEPTIONIST', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Scroll appointments using a cursor without a total count")
    public Mono<ResponseEntity<AppointmentScrollResponse>> findAll(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> ResponseEntity.ok(service.findAll(cursor, size, AppointmentAccess.from(jwt))));
    }

    @PatchMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('PATIENT', 'RECEPTIONIST', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Reschedule an appointment atomically")
    public Mono<ResponseEntity<AppointmentResponse>> reschedule(
            @PathVariable UUID id, @Valid @RequestBody RescheduleAppointmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> {
            return ResponseEntity.ok(service.reschedule(id, request, AppointmentAccess.from(jwt)));
        });
    }

    @PatchMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Complete an appointment")
    public Mono<ResponseEntity<AppointmentResponse>> complete(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> {
            return ResponseEntity.ok(service.complete(id, AppointmentAccess.from(jwt)));
        });
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'RECEPTIONIST', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Cancel an appointment")
    public Mono<ResponseEntity<Void>> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> {
            service.cancel(id, AppointmentAccess.from(jwt));
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/{id}/procedures")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Add a performed procedure with a price snapshot")
    public Mono<ResponseEntity<AppointmentResponse>> addProcedure(
            @PathVariable UUID id, @Valid @RequestBody AppointmentProcedureRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> {
            return ResponseEntity.ok(service.addProcedure(id, request, AppointmentAccess.from(jwt)));
        });
    }

    @PutMapping("/{appointmentId}/procedures/{itemId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Update a performed procedure")
    public Mono<ResponseEntity<AppointmentResponse>> updateProcedure(
            @PathVariable UUID appointmentId,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateAppointmentProcedureRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> {
            return ResponseEntity.ok(service.updateProcedure(appointmentId, itemId, request, AppointmentAccess.from(jwt)));
        });
    }

    @DeleteMapping("/{appointmentId}/procedures/{itemId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Delete a performed procedure")
    public Mono<ResponseEntity<Void>> removeProcedure(@PathVariable UUID appointmentId, @PathVariable UUID itemId,
            @AuthenticationPrincipal Jwt jwt) {
        return blocking(() -> {
            service.removeProcedure(appointmentId, itemId, AppointmentAccess.from(jwt));
            return ResponseEntity.noContent().build();
        });
    }

    private <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
