package ru.itmo.dentistry.appointment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.itmo.dentistry.appointment.common.BusinessValidationException;

@DisplayName("Курсор прокрутки приёмов")
class AppointmentCursorCodecTest {

    private final AppointmentCursorCodec codec = new AppointmentCursorCodec();

    @Test
    @DisplayName("Дата и UUID сохраняются после кодирования и декодирования курсора")
    void roundTripsCursor() {
        var cursor = new AppointmentCursor(Instant.parse("2026-10-23T07:00:00Z"), UUID.randomUUID());
        assertEquals(cursor, codec.decode(codec.encode(cursor)));
    }

    @Test
    @DisplayName("Некорректный курсор отклоняется")
    void rejectsMalformedCursor() {
        assertThrows(BusinessValidationException.class, () -> codec.decode("not-a-cursor"));
    }
}
