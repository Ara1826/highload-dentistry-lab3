package ru.itmo.dentistry.appointment.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogFeignConfiguration {
    @Bean
    RequestInterceptor internalCatalogToken(@Value("${dentistry.security.internal-api-token}") String token) {
        return template -> template.header("X-Internal-Token", token);
    }
}
