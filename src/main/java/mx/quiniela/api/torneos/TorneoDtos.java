package mx.quiniela.api.torneos;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TorneoDtos {

    private TorneoDtos() {
    }

    /** estado: proximo | en_curso | finalizado. */
    public record TorneoDto(Integer id, String nombre, String estado, LocalDate fechaInicio, LocalDate fechaFin,
                            int totalJornadas, int jornadasFinalizadas) {
    }

    public record TorneoRequest(
            @NotBlank(message = "Escribe el nombre del torneo")
            @Size(min = 3, max = 50, message = "Entre 3 y 50 caracteres") String nombre,
            LocalDate fechaInicio,
            LocalDate fechaFin) {
    }
}
