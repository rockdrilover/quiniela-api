package mx.quiniela.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import mx.quiniela.api.config.SeguridadProperties;

/**
 * Cuenta los logins fallidos por IP en una ventana deslizante (en memoria).
 * Frena a quien prueba muchas cuentas distintas desde el mismo lugar, algo que el bloqueo por cuenta no detecta.
 * Al reiniciar la API el conteo empieza de cero; para un solo servidor es suficiente.
 */
@Component
public class LimiteIpLogin {

    private final ConcurrentHashMap<String, Deque<Instant>> fallos = new ConcurrentHashMap<>();
    private final int maxFallos;
    private final Duration ventana;
    private final Clock clock;

    public LimiteIpLogin(SeguridadProperties props, Clock clock) {
        this.maxFallos = props.loginMaxFallosIp();
        this.ventana = props.loginBloqueo();
        this.clock = clock;
    }

    /** Si la IP ya superó el límite, devuelve cuánto falta para que pueda volver a intentar. */
    public Optional<Duration> esperaPara(String ip) {
        Deque<Instant> q = fallos.get(ip);
        if (q == null) return Optional.empty();
        synchronized (q) {
            Instant ahora = clock.instant();
            purgar(q, ahora);
            if (q.size() < maxFallos) return Optional.empty();
            return Optional.of(Duration.between(ahora, q.peekFirst().plus(ventana)));
        }
    }

    public void registrarFallo(String ip) {
        Deque<Instant> q = fallos.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant ahora = clock.instant();
            purgar(q, ahora);
            q.addLast(ahora);
        }
    }

    /** Para pruebas. */
    public void limpiar() {
        fallos.clear();
    }

    private void purgar(Deque<Instant> q, Instant ahora) {
        Instant limite = ahora.minus(ventana);
        while (!q.isEmpty() && !q.peekFirst().isAfter(limite)) {
            q.pollFirst();
        }
    }
}
