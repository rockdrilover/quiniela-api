package mx.quiniela.api.comun;

import org.springframework.http.HttpStatus;

/** Error de negocio que se devuelve al cliente como ProblemDetail con un mensaje en español. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;

    public ApiException(HttpStatus status, String codigo, String mensaje) {
        super(mensaje);
        this.status = status;
        this.codigo = codigo;
    }

    public HttpStatus getStatus() { return status; }
    public String getCodigo() { return codigo; }

    public static ApiException noAutorizado(String codigo, String mensaje) {
        return new ApiException(HttpStatus.UNAUTHORIZED, codigo, mensaje);
    }

    public static ApiException prohibido(String codigo, String mensaje) {
        return new ApiException(HttpStatus.FORBIDDEN, codigo, mensaje);
    }

    public static ApiException invalido(String codigo, String mensaje) {
        return new ApiException(HttpStatus.BAD_REQUEST, codigo, mensaje);
    }

    /** 429: el cliente debe esperar "segundos" antes de reintentar. */
    public static ApiException demasiadosIntentos(long segundos) {
        long minutos = Math.max(1, (segundos + 59) / 60);
        return new DemasiadosIntentos(segundos,
                "Demasiados intentos fallidos. Intenta de nuevo en " + minutos + (minutos == 1 ? " minuto" : " minutos"));
    }

    public static class DemasiadosIntentos extends ApiException {
        private final long segundos;

        DemasiadosIntentos(long segundos, String mensaje) {
            super(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADOS_INTENTOS", mensaje);
            this.segundos = Math.max(1, segundos);
        }

        public long getSegundos() { return segundos; }
    }
}
