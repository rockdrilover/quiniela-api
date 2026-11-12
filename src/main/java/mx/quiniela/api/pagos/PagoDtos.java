package mx.quiniela.api.pagos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PagoDtos {

    private PagoDtos() {
    }

    /**
     * Lo que ve el dueño de la quiniela de su último pago.
     * tipo "manual": el admin registró que le pagó por fuera (no hay archivo que ver).
     */
    public record ComprobanteDto(Integer id, String tipo, String estado, Instant subidoAt, String mimeType,
                                 String motivoRechazo) {
        public static ComprobanteDto de(Comprobante c) {
            return c == null ? null
                    : new ComprobanteDto(c.getId(), c.getTipo().name(), c.getEstado().name(), c.getSubidoAt(),
                            c.getMimeType(), c.getMotivoRechazo());
        }
    }

    /** puedeSubir: la quiniela espera pago (o fue rechazada, o su comprobante sigue sin revisar) y la jornada no ha cerrado. */
    public record PagoDto(boolean puedeSubir, ComprobanteDto comprobante) {
    }

    public record QuinielaRef(Integer id, Integer folio, int numero, String estado, BigDecimal monto) {
    }

    public record UsuarioRef(Integer id, String username, String nombre, String avatar) {
    }

    public record JornadaRef(Integer id, String nombre) {
    }

    /** Pantalla 7: una fila de la lista de validación. */
    public record ComprobanteAdminDto(
            Integer id,
            /** "comprobante" (archivo del usuario) o "manual" (registrado por el admin, sin archivo). */
            String tipo,
            String estado,
            Instant subidoAt,
            String mimeType,
            Integer tamanoBytes,
            /** true cuando el admin ya abrió el archivo (requisito para validar o rechazar). */
            boolean visto,
            Instant revisadoAt,
            String motivoRechazo,
            String nota,
            QuinielaRef quiniela,
            UsuarioRef usuario,
            JornadaRef jornada) {
    }

    /** conteos: en_revision, aprobado, rechazado y por_cobrar (quinielas enviadas sin pago en revisión). */
    public record ListaPagosDto(Map<String, Long> conteos, List<ComprobanteAdminDto> elementos) {
    }

    /** Quiniela enviada que espera pago: el admin puede registrarlo a mano si le pagaron por fuera. */
    public record PorCobrarDto(QuinielaRef quiniela, UsuarioRef usuario, JornadaRef jornada, Instant enviadaAt,
                               String motivoRechazo) {
    }

    public record RechazoRequest(
            @NotBlank(message = "Escribe el motivo del rechazo")
            @Size(min = 3, max = 255, message = "Entre 3 y 255 caracteres") String motivo) {
    }

    public record PagoManualRequest(@Size(max = 255, message = "Máximo 255 caracteres") String nota) {
    }
}
