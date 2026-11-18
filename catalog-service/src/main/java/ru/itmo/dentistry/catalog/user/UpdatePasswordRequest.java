package ru.itmo.dentistry.catalog.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePasswordRequest(
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min = 8, max = 72) String password) {

    @Override
    public String toString() {
        return "UpdatePasswordRequest";
    }
}
