package ru.itmo.dentistry.catalog.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.dentistry.catalog.procedure.ProcedureService;
import ru.itmo.dentistry.catalog.user.UserService;

@RestController
@Hidden
@RequiredArgsConstructor
@RequestMapping("/internal")
public class InternalCatalogController {

    private final UserService userService;
    private final ProcedureService procedureService;
    @Value("${dentistry.security.internal-api-token}")
    private final String internalToken;

    @GetMapping("/users/{id}")
    Mono<CatalogUserView> user(
            @PathVariable UUID id, @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        verifyToken(token);
        return userService.findInternalById(id);
    }

    @GetMapping("/procedures/{id}")
    Mono<CatalogProcedureView> procedure(
            @PathVariable UUID id, @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        verifyToken(token);
        return procedureService.findById(id)
                .map(procedure -> new CatalogProcedureView(
                        procedure.id(), procedure.name(), procedure.basePrice(), procedure.active()));
    }

    private void verifyToken(String token) {
        if (internalToken.isBlank() || token == null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
    }
}
