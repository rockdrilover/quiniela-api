package mx.quiniela.api.ranking;

import java.math.BigDecimal;
import java.util.List;

import mx.quiniela.api.jornadas.JornadaDtos.EstadoVisual;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDto;
import mx.quiniela.api.jornadas.JornadaDtos.PartidoDto;

public final class RankingDtos {

    private RankingDtos() {
    }

    /** Para el selector de jornada. */
    public record JornadaOpcionDto(Integer id, int numero, String nombre, EstadoVisual estadoVisual) {
    }

    public record JornadasRankingDto(Integer actual, List<JornadaOpcionDto> jornadas) {
    }

    public record BolsaDto(int participantes, BigDecimal recaudado, BigDecimal bolsa) {
    }

    public record CeldaDto(Integer idPartido, int golesLocal, int golesVisitante, String seleccion, Integer puntos, boolean exacto) {
    }

    public record FilaDto(
            int posicion,
            Integer idQuiniela,
            Integer folio,
            int numero,
            Integer idUsuario,
            String username,
            String avatar,
            /** El usuario tiene más de una quiniela pagada en esta jornada (para mostrar "Q2"). */
            boolean variasQuinielas,
            int puntos,
            int exactos,
            boolean esMia,
            /** Solo cuando la jornada está finalizada: posición 1 y su parte de la bolsa. */
            boolean ganador,
            BigDecimal premio,
            /** Vacío mientras la jornada acepte pronósticos (salvo las propias), para que nadie copie. */
            List<CeldaDto> pronosticos) {
    }

    public record RankingDto(
            JornadaDto jornada,
            List<PartidoDto> partidos,
            BolsaDto bolsa,
            boolean pronosticosVisibles,
            List<FilaDto> filas) {
    }
}
