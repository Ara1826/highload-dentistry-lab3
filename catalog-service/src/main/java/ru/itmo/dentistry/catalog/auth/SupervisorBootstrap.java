package ru.itmo.dentistry.catalog.auth;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.itmo.dentistry.catalog.user.UserAccount;
import ru.itmo.dentistry.catalog.user.UserRepository;
import ru.itmo.dentistry.catalog.user.UserRole;

@Component
@RequiredArgsConstructor
public class SupervisorBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    @Value("${dentistry.bootstrap.supervisor-email:}")
    private String email;
    @Value("${dentistry.bootstrap.supervisor-password:}")
    private String password;
    @Value("${dentistry.bootstrap.supervisor-name:Initial Supervisor}")
    private String fullName;
    @Value("${dentistry.bootstrap.supervisor-phone:+70000000000}")
    private String phone;

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isEmpty()) {
            return;
        }
        if (email.isBlank() || password.isBlank() || password.length() < 12 || !PasswordInput.fitsBCrypt(password)) {
            throw new IllegalStateException("Configure a bootstrap email and a password of at least 12 characters and at most 72 UTF-8 bytes");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // Startup runs outside the WebFlux event loop.
        var existing = users.findByEmailIgnoreCase(normalizedEmail).block();
        if (existing != null) {
            initializeExisting(existing).block();
            return;
        }

        var supervisor = UserAccount.create();
        supervisor.setFullName(fullName);
        supervisor.setPhone(phone);
        supervisor.setEmail(normalizedEmail);
        supervisor.setRole(UserRole.SUPERVISOR.name());
        supervisor.setPasswordHash(passwordEncoder.encode(password));
        users.save(supervisor)
                .then()
                .onErrorResume(DataIntegrityViolationException.class, error ->
                        users.findByEmailIgnoreCase(normalizedEmail)
                                .switchIfEmpty(Mono.error(error))
                                .flatMap(this::initializeExisting))
                .block();
    }

    private Mono<Void> initializeExisting(UserAccount existing) {
        if (!UserRole.SUPERVISOR.name().equals(existing.getRole())) {
            return Mono.error(new IllegalStateException("The bootstrap email already belongs to a non-supervisor account"));
        }
        if (!existing.isActive() || existing.getPasswordHash() != null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> passwordEncoder.encode(password))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hash -> users.initializeSupervisorPassword(existing.getId(), hash))
                .then();
    }
}
