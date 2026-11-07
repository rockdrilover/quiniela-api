package mx.quiniela.api.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj inyectable (UTC) para poder simular fechas en las pruebas. */
@Configuration
public class RelojConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
