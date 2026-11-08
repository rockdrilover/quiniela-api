package mx.quiniela.api.quinielas;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDetalleDto;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDto;
import mx.quiniela.api.pagos.PagoDtos.PagoDto;

public final class QuinielaDtos {

    private QuinielaDtos() {
    }

    public record QuinielaDto(
            Integer id,
            Integer folio,
            int numero,
            String estado,
            BigDecimal monto,
            Instant creadaAt,
            Instant enviadaAt,
            Instant actualizadaAt,
            Integer idJornada,
            int pronosticados,
            int totalPartidos,
            /** null mientras no haya partidos con marcador. */
            Integer puntos,
            int exactos,
            boolean editable) {
    }

    /** Pantalla 3: la jornada vigente y todas mis quinielas (con las jornadas a las que pertenecen). */
    public record MisQuinielasDto(
            /** Estado real de la cuenta (el del token puede ir atrasado hasta 15 min tras una aprobación). */
            String estadoUsuario,
            JornadaDto actual,
            boolean puedeCrear,
            String motivoNoPuedeCrear,
            List<JornadaDto> jornadas,
            List<QuinielaDto> quinielas) {
    }

    public record PronosticoDto(
            Integer idPartido,
            short golesLocal,
            short golesVisitante,
            String seleccion,
            /** null mientras el partido no tenga marcador. */
            Integer puntos,
            boolean exacto) {
    }

    /** Pantalla 6: la quiniela con su jornada, partidos y pronósticos (y puntos si ya hay marcadores). */
    public record QuinielaDetalleDto(QuinielaDto quiniela, JornadaDetalleDto jornada, List<PronosticoDto> pronosticos,
                                     PagoDto pago) {
    }

    public record PronosticoRequest(
            @NotNull(message = "Falta el partido") Integer idPartido,
            @NotNull(message = "Faltan los goles del local") @Min(value = 0, message = "No puede ser negativo") @Max(value = 20, message = "Máximo 20 goles") Short golesLocal,
            @NotNull(message = "Faltan los goles del visitante") @Min(value = 0, message = "No puede ser negativo") @Max(value = 20, message = "Máximo 20 goles") Short golesVisitante) {
    }

    /**
     * enviar=false guarda un borrador (puede ir incompleto). enviar=true exige todos los partidos
     * y la pasa a "pendiente de pago". Una quiniela ya enviada siempre debe quedar completa.
     */
    public record GuardarQuinielaRequest(
            Integer idJornada,
            @NotNull(message = "Faltan los pronósticos") @Size(max = 30) List<@Valid @NotNull PronosticoRequest> pronosticos,
            boolean enviar) {
    }
}
