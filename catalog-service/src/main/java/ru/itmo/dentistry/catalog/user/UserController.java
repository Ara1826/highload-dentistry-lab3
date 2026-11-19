package ru.itmo.dentistry.catalog.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Patients, doctors and clinic staff")
public class UserController {

    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('SUPERVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user", requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                    @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Patient", summary = "Create a patient", value = """
                                    {
                                      "fullName": "Иван Иванов",
                                      "phone": "+79995551903",
                                      "email": "patient.test3@example.com",
                                      "password": "PatientDemo123!",
                                      "role": "PATIENT"
                                    }
                                    """),
                            @ExampleObject(name = "Doctor", summary = "Create a doctor", value = """
                                    {
                                      "fullName": "Анна Смирнова",
                                      "phone": "+79995551904",
                                      "email": "doctor.test3@example.com",
                                      "password": "DoctorDemo123!",
                                      "role": "DOCTOR",
                                      "specializations": ["THERAPIST"],
                                      "workDayStart": "09:00:00",
                                      "workDayEnd": "18:00:00"
                                    }
                                    """)
                    })))
    public Mono<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN', 'RECEPTIONIST')")
    @Operation(summary = "Get a user by id")
    public Mono<UserResponse> findById(@PathVariable UUID id) {
        return userService.findById(id);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN', 'RECEPTIONIST')")
    @Operation(summary = "Get a paginated user list")
    public Mono<Page<UserResponse>> findAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) UserRole role) {
        return userService.findAll(page, size, role);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Operation(summary = "Update a user")
    public Mono<UserResponse> update(@PathVariable UUID id, @Valid @RequestBody UserRequest request) {
        return userService.update(id, request);
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set or replace an account password")
    public Mono<Void> updatePassword(@PathVariable UUID id, @Valid @RequestBody UpdatePasswordRequest request) {
        return userService.updatePassword(id, request.password());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a user")
    public Mono<Void> deactivate(@PathVariable UUID id) {
        return userService.deactivate(id);
    }

    @DeleteMapping("/{id}/permanent")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a user from the database")
    public Mono<Void> deletePermanently(@PathVariable UUID id) {
        return userService.deletePermanently(id);
    }

    @PutMapping("/{doctorId}/procedures/{procedureId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Assign a dental procedure to a doctor")
    public Mono<UserResponse> assignProcedure(@PathVariable UUID doctorId, @PathVariable UUID procedureId) {
        return userService.assignProcedure(doctorId, procedureId);
    }

    @DeleteMapping("/{doctorId}/procedures/{procedureId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Remove a dental procedure from a doctor")
    public Mono<UserResponse> removeProcedure(@PathVariable UUID doctorId, @PathVariable UUID procedureId) {
        return userService.removeProcedure(doctorId, procedureId);
    }
}
