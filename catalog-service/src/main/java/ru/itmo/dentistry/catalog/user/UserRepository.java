package ru.itmo.dentistry.catalog.user;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<UserAccount, UUID> {

    Mono<UserAccount> findByEmailIgnoreCase(String email);

    @Modifying
    @Query("""
            UPDATE users SET password_hash = :hash, version = version + 1
            WHERE id = :id AND role = 'SUPERVISOR' AND active AND password_hash IS NULL
            """)
    Mono<Integer> initializeSupervisorPassword(@Param("id") UUID id, @Param("hash") String hash);

    @Modifying
    @Query("UPDATE users SET password_hash = :hash, version = version + 1 WHERE id = :id")
    Mono<Integer> updatePassword(@Param("id") UUID id, @Param("hash") String hash);

    Flux<UserAccount> findBy(Pageable pageable);

    Flux<UserAccount> findByRole(String role, Pageable pageable);

    Mono<Long> countByRole(String role);

    Mono<Long> countByEmailIgnoreCaseAndIdNot(String email, UUID id);

    Mono<Long> countByPhoneAndIdNot(String phone, UUID id);
}
