package ru.itmo.dentistry.catalog.procedure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.itmo.dentistry.catalog.common.ConflictException;
import ru.itmo.dentistry.catalog.common.NotFoundException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Каталог стоматологических процедур")
class ProcedureServiceTest {

    @Mock
    private ProcedureRepository repository;

    @Mock
    private TransactionalOperator transactions;

    private ProcedureService service;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any(ProcedureAccount.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        service = new ProcedureService(repository, transactions, validator);
    }

    @Test
    @DisplayName("Процедура создаётся с удалением лишних пробелов")
    void createsProcedureAndTrimsName() {
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.create(request("  Cleaning  ", "  polish  ")))
                .assertNext(response -> {
                    assertEquals("Cleaning", response.name());
                    assertEquals("polish", response.description());
                    assertEquals(new BigDecimal("1500.00"), response.basePrice());
                    assertEquals(30, response.durationMinutes());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Повторное название процедуры отклоняется")
    void rejectsDuplicateName() {
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.create(request("Cleaning", null)))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("Отсутствующая процедура возвращает ошибку поиска")
    void missingProcedureIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(id))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Процедуру можно отключить")
    void deactivatesProcedure() {
        ProcedureAccount procedure = ProcedureAccount.create();
        procedure.setName("Cleaning");
        procedure.setBasePrice(new BigDecimal("1500.00"));
        procedure.setDurationMinutes(30);
        when(repository.findById(procedure.getId())).thenReturn(Mono.just(procedure));

        StepVerifier.create(service.deactivate(procedure.getId())).verifyComplete();

        assertFalse(procedure.isActive());
        verify(repository).save(procedure);
    }

    private static DentalProcedureRequest request(String name, String description) {
        return new DentalProcedureRequest(name, description, new BigDecimal("1500.00"), 30);
    }

    @Test
    @DisplayName("Процедура обновляется, а список возвращается с общим количеством")
    void updatesAndListsProcedures() {
        var procedure = ProcedureAccount.create();
        procedure.setName("Old");
        procedure.setBasePrice(BigDecimal.ONE);
        procedure.setDurationMinutes(10);
        when(repository.findById(procedure.getId())).thenReturn(Mono.just(procedure));
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        StepVerifier.create(service.update(procedure.getId(), request(" New ", null)))
                .assertNext(response -> assertEquals("New", response.name())).verifyComplete();
        when(repository.findBy(any())).thenReturn(reactor.core.publisher.Flux.just(procedure));
        when(repository.count()).thenReturn(Mono.just(1L));
        StepVerifier.create(service.findAll(0, 20))
                .assertNext(page -> assertEquals(1, page.total())).verifyComplete();
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(1L));
        StepVerifier.create(service.update(procedure.getId(), request("Duplicate", null)))
                .expectError(ConflictException.class).verify();
    }

    @Test
    @DisplayName("Сущность процедуры отклоняет отрицательную цену")
    void validatesEntity() {
        when(repository.countByNameIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        StepVerifier.create(service.create(new DentalProcedureRequest("Invalid", null, BigDecimal.valueOf(-1), 30)))
                .expectError(jakarta.validation.ConstraintViolationException.class).verify();
    }
}
