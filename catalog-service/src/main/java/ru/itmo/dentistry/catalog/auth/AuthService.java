package ru.itmo.dentistry.catalog.auth;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.itmo.dentistry.catalog.user.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;

    public Mono<LoginResponse> login(LoginRequest request) {
        if (!PasswordInput.fitsBCrypt(request.password())) {
            return invalidCredentials();
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        return users.findByEmailIgnoreCase(email)
                .flatMap(user -> Mono.fromCallable(() -> user.isActive() && user.getPasswordHash() != null
                                && passwordEncoder.matches(request.password(), user.getPasswordHash()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .filter(Boolean::booleanValue)
                        .map(valid -> tokens.issue(user.getId(), user.getEmail(), user.getRole())))
                .switchIfEmpty(invalidCredentials());
    }

    private Mono<LoginResponse> invalidCredentials() {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
    }
}
