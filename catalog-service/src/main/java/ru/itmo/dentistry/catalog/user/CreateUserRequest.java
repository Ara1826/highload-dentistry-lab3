package ru.itmo.dentistry.catalog.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.Set;

/** Request used only by SUPERVISOR to create an account. Password is write-only input. */
public record CreateUserRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Pattern(regexp = "^\\+?[0-9() -]{7,32}$") String phone,
        @NotBlank @Email @Size(max = 320) String email,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull UserRole role,
        Set<DoctorSpecialization> specializations,
        LocalTime workDayStart,
        LocalTime workDayEnd) {

    public UserRequest profile() {
        return new UserRequest(fullName, phone, email, role, specializations, workDayStart, workDayEnd);
    }

    @Override
    public String toString() {
        return "CreateUserRequest[profile=" + profile() + "]";
    }
}
