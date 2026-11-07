package mx.quiniela.api.catalogos;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Catálogos de solo lectura para cualquier usuario con sesión. */
@RestController
@RequestMapping("/api")
public class CatalogosController {

    private final EquipoRepository equipos;

    public CatalogosController(EquipoRepository equipos) {
        this.equipos = equipos;
    }

    public record EquipoDto(Integer id, String nombre, String abreviatura, String urlLogo) {
        public static EquipoDto de(Equipo e) {
            return e == null ? null : new EquipoDto(e.getId(), e.getNombre(), e.getAbreviatura(), e.getUrlLogo());
        }
    }

    @GetMapping("/equipos")
    public List<EquipoDto> equipos() {
        return equipos.findByActivoTrueOrderByNombre().stream().map(EquipoDto::de).toList();
    }
}
