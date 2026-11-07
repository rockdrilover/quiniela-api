package mx.quiniela.api.perfil;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PerfilDtos {

    private PerfilDtos() {
    }

    public record DatosUsuarioDto(Integer id, String username, String nombre, String correo, String rol, String estado,
                                  Instant fechaRegistro, Instant ultimoLogin) {
    }

    /** Estadísticas personales (solo quinielas con pago validado). */
    public record EstadisticasDto(int quinielasJugadas, int puntosTotales, int exactosTotales, int quinielasGanadas,
                                  BigDecimal premiosGanados) {
    }

    /** Una quiniela pagada en el historial: cómo le fue en esa jornada. */
    public record HistorialDto(Integer idQuiniela, Integer folio, int numero, Integer idJornada, String jornada,
                               String estadoJornada, int posicion, int participantes, int puntos, int exactos,
                               boolean ganador, BigDecimal premio) {
    }

    public record PerfilDto(DatosUsuarioDto usuario, EstadisticasDto estadisticas, List<HistorialDto> historial) {
    }

    public record EditarPerfilRequest(
            @NotBlank(message = "Escribe tu nombre") @Size(max = 100, message = "Máximo 100 caracteres") String nombre) {
    }

    public record EditarUsuarioRequest(
            @NotBlank(message = "Escribe el nombre") @Size(max = 100, message = "Máximo 100 caracteres") String nombre,
            @NotBlank(message = "Escribe el correo") @Email(message = "El correo no es válido")
            @Size(max = 254, message = "El correo es demasiado largo") String correo) {
    }
}
