package mx.quiniela.api.jornadas;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import mx.quiniela.api.catalogos.CatalogosController.EquipoDto;

public final class JornadaDtos {

    private JornadaDtos() {
    }

    /**
     * Estado que ve la gente (combina el estado guardado con la hora del primer partido):
     * BORRADOR → ACTIVA (acepta pronósticos) → EN_JUEGO (ya empezó el primer partido) → FINALIZADA.
     * CERRADA = el admin la cerró antes de que empiece el primer partido.
     */
    public enum EstadoVisual { BORRADOR, ACTIVA, CERRADA, EN_JUEGO, FINALIZADA }

    public record JornadaDto(
            Integer id,
            Integer numero,
            String nombre,
            String estado,
            EstadoVisual estadoVisual,
            boolean visible,
            BigDecimal precio,
            BigDecimal pctPremio,
            Instant fechaCierre,
            int totalPartidos,
            int partidosFinalizados,
            long totalQuinielas,
            boolean aceptaPronosticos) {
    }

    public record JornadaDetalleDto(JornadaDto jornada, List<PartidoDto> partidos) {
    }

    public record ListaJornadasDto(Integer idTemporada, String temporada, List<JornadaDto> jornadas) {
    }

    public record PartidoDto(
            Integer id,
            int orden,
            EquipoDto local,
            EquipoDto visitante,
            Instant fechaHora,
            Short golesLocal,
            Short golesVisitante,
            String resultado,
            String estado) {
    }

    public record CrearJornadaRequest(
            @Size(max = 100, message = "Máximo 100 caracteres") String nombre,
            @NotNull(message = "Indica el precio por quiniela")
            @DecimalMin(value = "1", message = "El precio mínimo es $1")
            @Digits(integer = 8, fraction = 2, message = "Precio inválido") BigDecimal precio,
            @DecimalMin(value = "0", message = "Entre 0 y 100")
            @DecimalMax(value = "100", message = "Entre 0 y 100") BigDecimal pctPremio) {
    }

    public record VisibleRequest(@NotNull Boolean visible) {
    }

    public record PartidoRequest(
            @NotNull(message = "Elige el equipo local") Integer idLocal,
            @NotNull(message = "Elige el equipo visitante") Integer idVisitante,
            @NotNull(message = "Indica fecha y hora") Instant fechaHora) {
    }

    public record ResultadoRequest(
            @NotNull(message = "Goles del local") @Min(value = 0, message = "No puede ser negativo") @Max(value = 30, message = "Máximo 30") Short golesLocal,
            @NotNull(message = "Goles del visitante") @Min(value = 0, message = "No puede ser negativo") @Max(value = 30, message = "Máximo 30") Short golesVisitante) {
    }
}
