package ru.itmo.dentistry.catalog.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.itmo.dentistry.catalog.common.BusinessValidationException;
import ru.itmo.dentistry.catalog.common.ConflictException;
import ru.itmo.dentistry.catalog.common.NotFoundException;
import ru.itmo.dentistry.catalog.procedure.ProcedureRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Учётные записи пользователей")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProcedureRepository procedureRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TransactionalOperator transactions;

    private UserService service;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        stubDatabase();
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        service = new UserService(userRepository, procedureRepository, databaseClient, transactions, validator, passwordEncoder);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$test-hash");
    }

    @Test
    @DisplayName("Создание пациента нормализует имя и email и хеширует пароль")
    void createsPatientAndNormalizesEmail() {
        when(userRepository.countByEmailIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        when(userRepository.countByPhoneAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.create(createPatient("  Ivan Petrov  ", " Ivan@Example.com ")))
                .assertNext(response -> {
                    assertEquals("Ivan Petrov", response.fullName());
                    assertEquals("ivan@example.com", response.email());
                    assertEquals(UserRole.PATIENT, response.role());
                })
                .verifyComplete();
        verify(passwordEncoder).encode("Password123!");
    }

    @Test
    @DisplayName("Повторный email отклоняется")
    void rejectsDuplicateEmail() {
        when(userRepository.countByEmailIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(1L));
        when(userRepository.countByPhoneAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.create(createPatient("Ivan Petrov", "ivan@example.com")))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("У пациента не может быть специализаций и рабочих часов врача")
    void rejectsDoctorFieldsOnPatient() {
        var request = new UserRequest(
                "Ivan Petrov",
                "+79990000000",
                "ivan@example.com",
                UserRole.PATIENT,
                Set.of(DoctorSpecialization.THERAPIST),
                null,
                null);

        assertThrows(BusinessValidationException.class, () -> service.create(new CreateUserRequest(request.fullName(), request.phone(), request.email(), "Password123!", request.role(), request.specializations(), request.workDayStart(), request.workDayEnd())));
    }

    @Test
    @DisplayName("Для врача обязательна хотя бы одна специализация")
    void rejectsDoctorWithoutSpecializations() {
        var request = new UserRequest(
                "Anna Smirnova",
                "+79990000001",
                "anna@example.com",
                UserRole.DOCTOR,
                Set.of(),
                LocalTime.of(9, 0),
                LocalTime.of(18, 0));

        assertThrows(BusinessValidationException.class, () -> service.create(new CreateUserRequest(request.fullName(), request.phone(), request.email(), "Password123!", request.role(), request.specializations(), request.workDayStart(), request.workDayEnd())));
    }

    @Test
    @DisplayName("Деактивация отключает учётную запись")
    void deactivatesUser() {
        UserAccount user = UserAccount.create();
        user.setFullName("Ivan Petrov");
        user.setPhone("+79990000000");
        user.setEmail("ivan@example.com");
        user.setRole(UserRole.PATIENT.name());
        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));

        StepVerifier.create(service.deactivate(user.getId())).verifyComplete();

        assertFalse(user.isActive());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("Пользователь удаляется из базы")
    void deletesUserPermanently() {
        UserAccount user = UserAccount.create();
        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.delete(user)).thenReturn(Mono.empty());

        StepVerifier.create(service.deletePermanently(user.getId())).verifyComplete();

        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("Удаление отсутствующего пользователя возвращает ошибку")
    void missingUserCannotBeDeleted() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.deletePermanently(id))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Назначать процедуры можно только врачу")
    void onlyDoctorCanReceiveProcedure() {
        UserAccount patient = UserAccount.create();
        patient.setRole(UserRole.PATIENT.name());
        when(userRepository.findById(patient.getId())).thenReturn(Mono.just(patient));

        StepVerifier.create(service.assignProcedure(patient.getId(), UUID.randomUUID()))
                .expectError(BusinessValidationException.class)
                .verify();
    }

    private static CreateUserRequest createPatient(String fullName, String email) {
        return new CreateUserRequest(fullName, "+79990000000", email, "Password123!", UserRole.PATIENT, null, null, null);
    }

    @Test
    @DisplayName("Создание врача сохраняет рабочие часы и специализацию")
    void createsDoctor() {
        when(userRepository.countByEmailIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        when(userRepository.countByPhoneAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        var request = new CreateUserRequest("Doctor", "+79990000001", "doctor@example.com",
                "Password123!", UserRole.DOCTOR, Set.of(DoctorSpecialization.THERAPIST),
                LocalTime.of(9, 0), LocalTime.of(18, 0));
        StepVerifier.create(service.create(request)).assertNext(user -> {
            assertEquals(UserRole.DOCTOR, user.role());
            assertEquals(LocalTime.of(9, 0), user.workDayStart());
        }).verifyComplete();
    }

    @Test
    @DisplayName("Справочник пользователей возвращает профиль и внутренний безопасный DTO")
    void readsProfilesAndPages() {
        var user = patient();
        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.findBy(any(Pageable.class))).thenReturn(Flux.just(user));
        when(userRepository.count()).thenReturn(Mono.just(1L));
        when(userRepository.findByRole(anyString(), any())).thenReturn(Flux.just(user));
        when(userRepository.countByRole(anyString())).thenReturn(Mono.just(1L));
        StepVerifier.create(service.findById(user.getId()))
                .assertNext(response -> assertEquals(user.getId(), response.id())).verifyComplete();
        StepVerifier.create(service.findInternalById(user.getId()))
                .assertNext(response -> assertEquals("PATIENT", response.role())).verifyComplete();
        StepVerifier.create(service.findAll(0, 20, null))
                .assertNext(page -> assertEquals(1, page.getTotalElements())).verifyComplete();
        StepVerifier.create(service.findAll(0, 20, UserRole.PATIENT))
                .assertNext(page -> assertEquals(1, page.getContent().size())).verifyComplete();
    }

    @Test
    @DisplayName("Обновление пользователя нормализует профиль и сохраняет пароль")
    void updatesProfile() {
        var user = patient();
        user.setPasswordHash("saved-hash");
        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.countByEmailIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        when(userRepository.countByPhoneAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        var request = new UserRequest(" Changed ", "+79990000000", "NEW@example.com",
                UserRole.PATIENT, null, null, null);
        StepVerifier.create(service.update(user.getId(), request)).assertNext(response -> {
            assertEquals("Changed", response.fullName());
            assertEquals("new@example.com", response.email());
        }).verifyComplete();
        assertEquals("saved-hash", user.getPasswordHash());
    }

    @Test
    @DisplayName("Дубликаты телефона и email отклоняются при создании и обновлении")
    void rejectsDuplicateContacts() {
        var user = patient();
        var request = new UserRequest("Patient", "+79990000000", "test@example.com",
                UserRole.PATIENT, null, null, null);
        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(userRepository.countByEmailIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(0L));
        when(userRepository.countByPhoneAndIdNot(anyString(), any())).thenReturn(Mono.just(1L));
        StepVerifier.create(service.create(createPatient("Patient", "test@example.com")))
                .expectError(ConflictException.class).verify();
        StepVerifier.create(service.update(user.getId(), request)).expectError(ConflictException.class).verify();
        when(userRepository.countByEmailIgnoreCaseAndIdNot(anyString(), any())).thenReturn(Mono.just(1L));
        StepVerifier.create(service.update(user.getId(), request)).expectError(ConflictException.class).verify();
    }

    @Test
    @DisplayName("Смена пароля записывает хеш, а отсутствующий пользователь возвращает ошибку")
    void changesPassword() {
        UUID id = UUID.randomUUID();
        when(userRepository.updatePassword(id, "$2a$12$test-hash")).thenReturn(Mono.just(1));
        StepVerifier.create(service.updatePassword(id, "Password123!")).verifyComplete();
        when(userRepository.updatePassword(id, "$2a$12$test-hash")).thenReturn(Mono.just(0));
        StepVerifier.create(service.updatePassword(id, "Password123!")).expectError(NotFoundException.class).verify();
        StepVerifier.create(service.updatePassword(id, "я".repeat(37)))
                .expectError(BusinessValidationException.class).verify();
    }

    @Test
    @DisplayName("Врачу можно назначить активную процедуру и затем удалить назначение")
    void assignsAndRemovesProcedure() {
        var user = patient();
        user.setRole("DOCTOR");
        var procedure = ru.itmo.dentistry.catalog.procedure.ProcedureAccount.create();
        procedure.setActive(true);
        when(userRepository.findById(user.getId())).thenReturn(Mono.just(user));
        when(procedureRepository.findById(procedure.getId())).thenReturn(Mono.just(procedure));
        StepVerifier.create(service.assignProcedure(user.getId(), procedure.getId()))
                .assertNext(response -> assertEquals(UserRole.DOCTOR, response.role())).verifyComplete();
        StepVerifier.create(service.removeProcedure(user.getId(), procedure.getId()))
                .expectNextCount(1).verifyComplete();
        procedure.setActive(false);
        StepVerifier.create(service.assignProcedure(user.getId(), procedure.getId()))
                .expectError(BusinessValidationException.class).verify();
        user.setRole("PATIENT");
        StepVerifier.create(service.removeProcedure(user.getId(), procedure.getId()))
                .expectError(BusinessValidationException.class).verify();
    }

    private static UserAccount patient() {
        var user = UserAccount.create();
        user.setFullName("Patient");
        user.setPhone("+79990000000");
        user.setEmail("test@example.com");
        user.setRole("PATIENT");
        return user;
    }

    @SuppressWarnings("unchecked")
    private void stubDatabase() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<java.util.Map<String, Object>> fetch = mock(FetchSpec.class);
        RowsFetchSpec<Object> rows = mock(RowsFetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.just(0L));
        when(spec.map(any(Function.class))).thenReturn(rows);
        when(rows.all()).thenReturn(Flux.empty());
        when(rows.one()).thenReturn(Mono.just(0L));
    }
}
