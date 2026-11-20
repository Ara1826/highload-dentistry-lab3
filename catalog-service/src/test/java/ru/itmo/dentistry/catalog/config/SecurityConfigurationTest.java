package ru.itmo.dentistry.catalog.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Проверка конфигурации ключа JWT")
class SecurityConfigurationTest {
    @ParameterizedTest(name = "Ключ JWT «{0}» отклоняется")
    @ValueSource(strings = {"", "short"})
    @DisplayName("Пустой и короткий ключ подписи останавливают запуск сервиса")
    void rejectsWeakSigningKeys(String secret) {
        assertThrows(IllegalStateException.class, () -> new SecurityConfiguration().jwtDecoder(secret));
    }
}

