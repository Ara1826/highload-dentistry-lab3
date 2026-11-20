package ru.itmo.dentistry.appointment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import ru.itmo.dentistry.appointment.catalogclient.CatalogGateway;
import ru.itmo.dentistry.appointment.catalogclient.CatalogProcedureView;
import ru.itmo.dentistry.appointment.catalogclient.CatalogUserView;
import ru.itmo.dentistry.appointment.common.BusinessValidationException;
import ru.itmo.dentistry.appointment.common.ConflictException;
import ru.itmo.dentistry.appointment.notification.AfterCommitNotificationPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Запись пациентов и права на приём")
class AppointmentServiceTest {

    private static final AppointmentAccess STAFF = new AppointmentAccess(UUID.randomUUID(), AppointmentAccess.Scope.STAFF);
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AppointmentProcedureRepository appointmentProcedureRepository;

    @Mock
    private CatalogGateway catalog;

    @Mock
    private AfterCommitNotificationPublisher notifications;

    @Mock
    private EntityManager entityManager;

    private AppointmentService service;

    @BeforeEach
    void setUp() {
        Query query = org.mockito.Mockito.mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1);
        service = new AppointmentService(
                appointmentRepository,
                appointmentProcedureRepository,
                catalog,
                notifications,
                new AppointmentCursorCodec(),
                entityManager,
                MOSCOW,
                30);
    }

    @Test
    @DisplayName("Пациент и врач должны быть разными пользователями")
    void rejectsSamePatientAndDoctor() {
        UUID id = UUID.randomUUID();
        var request = new CreateAppointmentRequest(id, id, Instant.parse("2026-11-06T07:00:00Z"),
                Instant.parse("2026-11-06T07:30:00Z"), null);

        assertThrows(BusinessValidationException.class, () -> service.create(request, STAFF));
    }

    @Test
    @DisplayName("Пациенту запрещено записывать другого человека")
    void patientCannotBookForAnotherUser() {
        var patient = new AppointmentAccess(UUID.randomUUID(), AppointmentAccess.Scope.PATIENT);
        var request = new CreateAppointmentRequest(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-11-06T07:00:00Z"), Instant.parse("2026-11-06T07:30:00Z"), null);

        assertThrows(AccessDeniedException.class, () -> service.create(request, patient));
        verifyNoInteractions(catalog, notifications);
    }

    @Test
    @DisplayName("Врачу запрещено завершать чужой приём")
    void doctorCannotCompleteAnotherDoctorsAppointment() {
        UUID id = UUID.randomUUID();
        var appointment = appointment(id, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findLockedById(id)).thenReturn(Optional.of(appointment));
        var doctor = new AppointmentAccess(UUID.randomUUID(), AppointmentAccess.Scope.DOCTOR);

        assertThrows(AccessDeniedException.class, () -> service.complete(id, doctor));
        assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
    }

    @Test
    @DisplayName("Запись с неактивным пациентом или врачом отклоняется")
    void rejectsInactiveParticipants() {
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        when(catalog.user(patientId)).thenReturn(user(patientId, "PATIENT", false));
        when(catalog.user(doctorId)).thenReturn(doctor(doctorId));

        var request = new CreateAppointmentRequest(patientId, doctorId, Instant.parse("2026-11-06T07:00:00Z"),
                Instant.parse("2026-11-06T07:30:00Z"), "  checkup  ");

        BusinessValidationException error = assertThrows(BusinessValidationException.class, () -> service.create(request, STAFF));
        assertEquals("Patient and doctor must be active", error.getMessage());
    }

    @Test
    @DisplayName("Запись вне рабочего времени врача отклоняется")
    void rejectsSlotOutsideWorkingHours() {
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        when(catalog.user(patientId)).thenReturn(user(patientId, "PATIENT", true));
        when(catalog.user(doctorId)).thenReturn(doctor(doctorId));

        var request = new CreateAppointmentRequest(patientId, doctorId, Instant.parse("2026-11-06T04:00:00Z"),
                Instant.parse("2026-11-06T04:30:00Z"), null);

        assertThrows(BusinessValidationException.class, () -> service.create(request, STAFF));
    }

    @Test
    @DisplayName("Занятый интервал врача нельзя забронировать повторно")
    void rejectsOverlappingDoctor() {
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        when(catalog.user(patientId)).thenReturn(user(patientId, "PATIENT", true));
        when(catalog.user(doctorId)).thenReturn(doctor(doctorId));
        when(appointmentRepository.hasDoctorOverlap(eq(doctorId), any(), any(), eq(null))).thenReturn(true);

        var request = new CreateAppointmentRequest(patientId, doctorId, Instant.parse("2026-11-06T07:00:00Z"),
                Instant.parse("2026-11-06T07:30:00Z"), null);

        assertThrows(ConflictException.class, () -> service.create(request, STAFF));
    }

    @Test
    @DisplayName("Приём создаётся в рабочее время с уведомлением")
    void createsAppointmentInsideWorkingHours() {
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        when(catalog.user(patientId)).thenReturn(user(patientId, "PATIENT", true));
        when(catalog.user(doctorId)).thenReturn(doctor(doctorId));
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new CreateAppointmentRequest(patientId, doctorId, Instant.parse("2026-11-06T07:00:00Z"),
                Instant.parse("2026-11-06T07:30:00Z"), "  checkup  ");

        AppointmentResponse response = service.create(request, STAFF);

        assertEquals("Ivan Petrov", response.patientName());
        assertEquals("Anna Smirnova", response.doctorName());
        assertEquals(AppointmentStatus.SCHEDULED, response.status());
        assertEquals("checkup", response.comment());
        verify(notifications).created(response.id());
    }

    @Test
    @DisplayName("Завершённый приём нельзя отменить")
    void completedAppointmentCannotBeCancelled() {
        UUID id = UUID.randomUUID();
        Appointment appointment = appointment(id, AppointmentStatus.COMPLETED);
        when(appointmentRepository.findLockedById(id)).thenReturn(Optional.of(appointment));

        assertThrows(ConflictException.class, () -> service.cancel(id, STAFF));
    }

    @Test
    @DisplayName("Запланированный приём можно завершить")
    void completesScheduledAppointment() {
        UUID id = UUID.randomUUID();
        Appointment appointment = appointment(id, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findLockedById(id)).thenReturn(Optional.of(appointment));

        AppointmentResponse response = service.complete(id, STAFF);

        assertEquals(AppointmentStatus.COMPLETED, response.status());
    }

    @Test
    @DisplayName("Нельзя добавить процедуру, которую врач не выполняет")
    void rejectsProcedureTheDoctorDoesNotPerform() {
        UUID appointmentId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        Appointment appointment = appointment(appointmentId, AppointmentStatus.SCHEDULED);
        appointment.setDoctorId(doctorId);
        when(appointmentRepository.findLockedById(appointmentId)).thenReturn(Optional.of(appointment));
        when(catalog.procedure(procedureId))
                .thenReturn(new CatalogProcedureView(procedureId, "Cleaning", new BigDecimal("1500.00"), true));
        when(catalog.user(doctorId)).thenReturn(doctor(doctorId));

        var request = new AppointmentProcedureRequest(procedureId, 1, 11);

        BusinessValidationException error = assertThrows(
                BusinessValidationException.class, () -> service.addProcedure(appointmentId, request, STAFF));
        assertEquals("The doctor does not perform this procedure", error.getMessage());
    }

    @Test
    @DisplayName("Список свободного времени исключает занятый интервал")
    void availabilitySkipsOccupiedSlot() {
        UUID doctorId = UUID.randomUUID();
        when(catalog.user(doctorId)).thenReturn(doctor(doctorId));
        Appointment occupied = new Appointment();
        occupied.setStartsAt(Instant.parse("2026-11-06T06:00:00Z"));
        occupied.setEndsAt(Instant.parse("2026-11-06T06:30:00Z"));
        when(appointmentRepository.findByDoctorIdAndStatusAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAt(
                eq(doctorId), eq(AppointmentStatus.SCHEDULED), any(), any()))
                .thenReturn(List.of(occupied));

        List<AvailabilityResponse> slots = service.availability(doctorId, LocalDate.of(2026, 11, 6));

        assertTrue(slots.stream().noneMatch(slot -> slot.startsAt().equals(Instant.parse("2026-11-06T06:00:00Z"))));
        assertTrue(slots.stream().anyMatch(slot -> slot.startsAt().equals(Instant.parse("2026-11-06T06:30:00Z"))));
        assertEquals(17, slots.size());
    }

    private static CatalogUserView user(UUID id, String role, boolean active) {
        return new CatalogUserView(id, "Ivan Petrov", role, active, null, null, Set.of());
    }

    @Test
    @DisplayName("Курсор строится по последней видимой записи и исчезает на последней странице")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void scrollsVisibleAppointments() {
        var appointment = appointment(UUID.randomUUID(), AppointmentStatus.SCHEDULED);
        var created = Instant.parse("2026-11-01T10:00:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(appointment, "createdAt", created);
        var slice = new org.springframework.data.domain.SliceImpl<>(List.of(appointment),
                org.springframework.data.domain.PageRequest.of(0, 1), true);
        var query = org.mockito.Mockito.mock(
                org.springframework.data.jpa.repository.JpaSpecificationExecutor.SpecificationFluentQuery.class);
        when(query.slice(any())).thenReturn(slice);
        when(appointmentRepository.findBy(
                any(org.springframework.data.jpa.domain.Specification.class), any(java.util.function.Function.class)))
                .thenAnswer(call -> {
                    var filter = (org.springframework.data.jpa.domain.Specification<Appointment>) call.getArgument(0);
                    var root = org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
                    var builder = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaBuilder.class);
                    filter.toPredicate(root, null, builder);
                    return ((java.util.function.Function) call.getArgument(1)).apply(query);
                });
        var patient = new AppointmentAccess(appointment.getPatientId(), AppointmentAccess.Scope.PATIENT);
        var page = service.findAll(null, 1, patient);
        assertEquals(1, page.items().size());
        assertTrue(page.hasNext());
        assertEquals(new AppointmentCursor(created, appointment.getId()),
                new AppointmentCursorCodec().decode(page.nextCursor()));
        var last = new org.springframework.data.domain.SliceImpl<>(List.of(appointment),
                org.springframework.data.domain.PageRequest.of(0, 1), false);
        when(query.slice(any())).thenReturn(last);
        assertEquals(null, service.findAll(page.nextCursor(), 1, patient).nextCursor());
        service.findAll("", 1, new AppointmentAccess(appointment.getDoctorId(), AppointmentAccess.Scope.DOCTOR));
        service.findAll(null, 1, STAFF);
    }

    @Test
    @DisplayName("Чтение приёма разрешено владельцу, а чужой и отсутствующий приёмы отклоняются")
    void readsOnlyOwnedAppointment() {
        UUID id = UUID.randomUUID();
        var appointment = appointment(id, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findById(id)).thenReturn(Optional.of(appointment));
        var owner = new AppointmentAccess(appointment.getPatientId(), AppointmentAccess.Scope.PATIENT);
        assertEquals(id, service.findById(id, owner).id());
        assertThrows(AccessDeniedException.class, () -> service.findById(id,
                new AppointmentAccess(UUID.randomUUID(), AppointmentAccess.Scope.PATIENT)));
        assertThrows(ru.itmo.dentistry.appointment.common.NotFoundException.class,
                () -> service.findById(UUID.randomUUID(), STAFF));
    }

    @Test
    @DisplayName("Перенос сохраняет новый интервал и уведомляет только после проверки пересечений")
    void reschedulesAndCancels() {
        UUID id = UUID.randomUUID();
        var appointment = appointment(id, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findLockedById(id)).thenReturn(Optional.of(appointment));
        when(catalog.user(appointment.getPatientId())).thenReturn(user(appointment.getPatientId(), "PATIENT", true));
        when(catalog.user(appointment.getDoctorId())).thenReturn(doctor(appointment.getDoctorId()));
        var request = new RescheduleAppointmentRequest(Instant.parse("2026-11-06T08:00:00Z"),
                Instant.parse("2026-11-06T08:30:00Z"));
        assertEquals(request.startsAt(), service.reschedule(id, request, STAFF).startsAt());
        verify(notifications).rescheduled(id);
        service.cancel(id, STAFF);
        assertEquals(AppointmentStatus.CANCELLED, appointment.getStatus());
        verify(notifications).cancelled(id);
    }

    @Test
    @DisplayName("Добавление процедуры сохраняет цену-снимок, количество и итоговую стоимость")
    void procedureLifecycle() {
        UUID id = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        var appointment = appointment(id, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findLockedById(id)).thenReturn(Optional.of(appointment));
        when(catalog.procedure(procedureId)).thenReturn(
                new CatalogProcedureView(procedureId, "Cleaning", new BigDecimal("1500.00"), true));
        when(catalog.user(appointment.getDoctorId())).thenReturn(new CatalogUserView(
                appointment.getDoctorId(), "Doctor", "DOCTOR", true, LocalTime.of(9, 0),
                LocalTime.of(18, 0), Set.of(procedureId)));
        var added = service.addProcedure(id, new AppointmentProcedureRequest(procedureId, 2, 11), STAFF);
        assertEquals(new BigDecimal("3000.00"), added.totalPrice());
        var item = appointment.getProcedures().getFirst();
        UUID itemId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(item, "id", itemId);
        when(appointmentProcedureRepository.findByIdAndAppointmentId(itemId, id)).thenReturn(Optional.of(item));
        var updated = service.updateProcedure(id, itemId, new UpdateAppointmentProcedureRequest(3, 12), STAFF);
        assertEquals(new BigDecimal("4500.00"), updated.totalPrice());
        service.removeProcedure(id, itemId, STAFF);
        assertTrue(appointment.getProcedures().isEmpty());
        verify(appointmentProcedureRepository).delete(item);
    }

    @Test
    @DisplayName("Пропавшая строка процедуры и заблокированный приём возвращают ошибку поиска")
    void rejectsMissingResources() {
        UUID id = UUID.randomUUID();
        var appointment = appointment(id, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findLockedById(id)).thenReturn(Optional.of(appointment));
        assertThrows(ru.itmo.dentistry.appointment.common.NotFoundException.class,
                () -> service.updateProcedure(id, UUID.randomUUID(), new UpdateAppointmentProcedureRequest(1, null), STAFF));
        assertThrows(ru.itmo.dentistry.appointment.common.NotFoundException.class,
                () -> service.removeProcedure(id, UUID.randomUUID(), STAFF));
        assertThrows(ru.itmo.dentistry.appointment.common.NotFoundException.class,
                () -> service.complete(UUID.randomUUID(), STAFF));
    }

    @Test
    @DisplayName("Неактивная процедура и неверный врач не допускаются к приёму")
    void rejectsInactiveProcedureAndInvalidAvailability() {
        UUID id = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        var appointment = appointment(id, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findLockedById(id)).thenReturn(Optional.of(appointment));
        when(catalog.procedure(procedureId)).thenReturn(
                new CatalogProcedureView(procedureId, "Cleaning", BigDecimal.TEN, false));
        when(catalog.user(appointment.getDoctorId())).thenReturn(doctor(appointment.getDoctorId()));
        assertThrows(BusinessValidationException.class,
                () -> service.addProcedure(id, new AppointmentProcedureRequest(procedureId, 1, null), STAFF));
        when(catalog.user(id)).thenReturn(user(id, "PATIENT", true));
        assertThrows(BusinessValidationException.class, () -> service.availability(id, LocalDate.now()));
        when(catalog.user(id)).thenReturn(user(id, "DOCTOR", false));
        assertThrows(BusinessValidationException.class, () -> service.availability(id, LocalDate.now()));
    }

    private static CatalogUserView doctor(UUID id) {
        return new CatalogUserView(
                id,
                "Anna Smirnova",
                "DOCTOR",
                true,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                Set.of());
    }

    private static Appointment appointment(UUID id, AppointmentStatus status) {
        Appointment appointment = new Appointment();
        org.springframework.test.util.ReflectionTestUtils.setField(appointment, "id", id);
        appointment.setPatientId(UUID.randomUUID());
        appointment.setPatientName("Ivan Petrov");
        appointment.setDoctorId(UUID.randomUUID());
        appointment.setDoctorName("Anna Smirnova");
        appointment.setStartsAt(Instant.parse("2026-11-06T07:00:00Z"));
        appointment.setEndsAt(Instant.parse("2026-11-06T07:30:00Z"));
        appointment.setStatus(status);
        return appointment;
    }
}
