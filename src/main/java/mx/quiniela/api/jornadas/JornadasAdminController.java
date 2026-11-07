package mx.quiniela.api.jornadas;

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
import mx.quiniela.api.jornadas.JornadaDtos.CrearJornadaRequest;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDetalleDto;
import mx.quiniela.api.jornadas.JornadaDtos.ListaJornadasDto;
import mx.quiniela.api.jornadas.JornadaDtos.PartidoRequest;
import mx.quiniela.api.jornadas.JornadaDtos.ResultadoRequest;
import mx.quiniela.api.jornadas.JornadaDtos.VisibleRequest;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('admin')")
public class JornadasAdminController {

    private final JornadasAdminService servicio;

    public JornadasAdminController(JornadasAdminService servicio) {
        this.servicio = servicio;
    }

    // ---------------------------------------------------------------- jornadas

    @GetMapping("/jornadas")
    public ListaJornadasDto listar() {
        return servicio.listar();
    }

    @PostMapping("/jornadas")
    @ResponseStatus(HttpStatus.CREATED)
    public JornadaDetalleDto crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CrearJornadaRequest req) {
        return servicio.crear(admin(jwt), req.nombre(), req.precio(), req.pctPremio());
    }

    @GetMapping("/jornadas/{id}")
    public JornadaDetalleDto detalle(@PathVariable Integer id) {
        return servicio.detalle(id);
    }

    @PutMapping("/jornadas/{id}")
    public JornadaDetalleDto editar(@PathVariable Integer id, @Valid @RequestBody CrearJornadaRequest req) {
        return servicio.editar(id, req.nombre(), req.precio(), req.pctPremio());
    }

    @DeleteMapping("/jornadas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        servicio.eliminar(admin(jwt), id);
    }

    @PostMapping("/jornadas/{id}/abrir")
    public JornadaDetalleDto abrir(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return servicio.abrir(admin(jwt), id);
    }

    @PostMapping("/jornadas/{id}/cerrar")
    public JornadaDetalleDto cerrar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return servicio.cerrar(admin(jwt), id);
    }

    @PostMapping("/jornadas/{id}/finalizar")
    public JornadaDetalleDto finalizar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return servicio.finalizar(admin(jwt), id);
    }

    @PutMapping("/jornadas/{id}/visible")
    public JornadaDetalleDto visible(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id,
                                     @Valid @RequestBody VisibleRequest req) {
        return servicio.cambiarVisible(admin(jwt), id, req.visible());
    }

    // ---------------------------------------------------------------- partidos

    @PostMapping("/jornadas/{id}/partidos")
    @ResponseStatus(HttpStatus.CREATED)
    public JornadaDetalleDto agregarPartido(@PathVariable Integer id, @Valid @RequestBody PartidoRequest req) {
        return servicio.agregarPartido(id, req.idLocal(), req.idVisitante(), req.fechaHora());
    }

    @PutMapping("/partidos/{id}")
    public JornadaDetalleDto editarPartido(@PathVariable Integer id, @Valid @RequestBody PartidoRequest req) {
        return servicio.editarPartido(id, req.idLocal(), req.idVisitante(), req.fechaHora());
    }

    @DeleteMapping("/partidos/{id}")
    public JornadaDetalleDto eliminarPartido(@PathVariable Integer id) {
        return servicio.eliminarPartido(id);
    }

    @PutMapping("/partidos/{id}/resultado")
    public JornadaDetalleDto resultado(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id,
                                       @Valid @RequestBody ResultadoRequest req) {
        return servicio.capturarResultado(admin(jwt), id, req.golesLocal(), req.golesVisitante());
    }

    @PostMapping("/partidos/{id}/suspender")
    public JornadaDetalleDto suspender(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return servicio.suspenderPartido(admin(jwt), id);
    }

    private static Integer admin(Jwt jwt) {
        return Integer.valueOf(jwt.getSubject());
    }
}
