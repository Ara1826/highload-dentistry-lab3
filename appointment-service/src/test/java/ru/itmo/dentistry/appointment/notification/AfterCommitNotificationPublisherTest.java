package ru.itmo.dentistry.appointment.notification;

import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("Доставка уведомлений после фиксации транзакции")
class AfterCommitNotificationPublisherTest {
    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("При commit доставляются уведомления создания, переноса и отмены")
    void deliversOnlyAfterCommit() {
        var notifications = mock(NotificationService.class);
        var publisher = new AfterCommitNotificationPublisher(notifications);
        UUID id = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
        publisher.created(id);
        publisher.rescheduled(id);
        publisher.cancelled(id);
        verifyNoInteractions(notifications);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(notifications).appointmentCreated(id);
        verify(notifications).appointmentRescheduled(id);
        verify(notifications).appointmentCancelled(id);
    }

    @Test
    @DisplayName("Откат транзакции не отправляет уведомление")
    void rollbackDoesNotNotify() {
        var notifications = mock(NotificationService.class);
        var publisher = new AfterCommitNotificationPublisher(notifications);
        TransactionSynchronizationManager.initSynchronization();
        publisher.created(UUID.randomUUID());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("Вне транзакции события сразу передаются настроенному сервису уведомлений")
    void deliversWithoutTransaction() {
        var publisher = new AfterCommitNotificationPublisher(new LoggingNotificationService());
        UUID id = UUID.randomUUID();
        publisher.created(id);
        publisher.rescheduled(id);
        publisher.cancelled(id);
    }
}
