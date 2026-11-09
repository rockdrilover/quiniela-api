package mx.quiniela.api.torneos;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mx.quiniela.api.torneos.TorneoDtos.TorneoDto;
import mx.quiniela.api.torneos.TorneoDtos.TorneoRequest;

@RestController
@RequestMapping("/api/admin/torneos")
@PreAuthorize("hasRole('admin')")
public class TorneosController {

    private final TorneosService servicio;

    public TorneosController(TorneosService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<TorneoDto> listar() {
        return servicio.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TorneoDto crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TorneoRequest req) {
        return servicio.crear(admin(jwt), req.nombre(), req.fechaInicio(), req.fechaFin());
    }

    @PutMapping("/{id}")
    public TorneoDto editar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id, @Valid @RequestBody TorneoRequest req) {
        return servicio.editar(admin(jwt), id, req.nombre(), req.fechaInicio(), req.fechaFin());
    }

    @PostMapping("/{id}/iniciar")
    public TorneoDto iniciar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return servicio.iniciar(admin(jwt), id);
    }

    @PostMapping("/{id}/finalizar")
    public TorneoDto finalizar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return servicio.finalizar(admin(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        servicio.eliminar(admin(jwt), id);
    }

    private static Integer admin(Jwt jwt) {
        return Integer.valueOf(jwt.getSubject());
    }
}
