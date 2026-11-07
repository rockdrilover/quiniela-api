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

    /** Lo que ve el dueño de la quiniela de su último comprobante. */
    public record ComprobanteDto(Integer id, String estado, Instant subidoAt, String mimeType, String motivoRechazo) {
        public static ComprobanteDto de(Comprobante c) {
            return c == null ? null
                    : new ComprobanteDto(c.getId(), c.getEstado().name(), c.getSubidoAt(), c.getMimeType(), c.getMotivoRechazo());
        }
    }

    /** puedeSubir: la quiniela espera pago (o fue rechazada) y la jornada no ha cerrado. */
    public record PagoDto(boolean puedeSubir, ComprobanteDto comprobante) {
    }

    public record QuinielaRef(Integer id, Integer folio, int numero, String estado, BigDecimal monto) {
    }

    public record UsuarioRef(Integer id, String username, String nombre) {
    }

    public record JornadaRef(Integer id, String nombre) {
    }

    /** Pantalla 7: una fila de la lista de validación. */
    public record ComprobanteAdminDto(
            Integer id,
            String estado,
            Instant subidoAt,
            String mimeType,
            int tamanoBytes,
            /** true cuando el admin ya abrió el archivo (requisito para validar o rechazar). */
            boolean visto,
            Instant revisadoAt,
            String motivoRechazo,
            QuinielaRef quiniela,
            UsuarioRef usuario,
            JornadaRef jornada) {
    }

    public record ListaPagosDto(Map<String, Long> conteos, List<ComprobanteAdminDto> elementos) {
    }

    public record RechazoRequest(
            @NotBlank(message = "Escribe el motivo del rechazo")
            @Size(min = 3, max = 255, message = "Entre 3 y 255 caracteres") String motivo) {
    }
}
