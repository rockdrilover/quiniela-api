package mx.quiniela.api.quinielas;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDetalleDto;
import mx.quiniela.api.quinielas.QuinielaDtos.GuardarQuinielaRequest;
import mx.quiniela.api.quinielas.QuinielaDtos.MisQuinielasDto;
import mx.quiniela.api.quinielas.QuinielaDtos.QuinielaDetalleDto;

/** Quinielas del usuario con sesión (cualquier rol). */
@RestController
@RequestMapping("/api")
public class QuinielasController {

    private final QuinielasService servicio;

    public QuinielasController(QuinielasService servicio) {
        this.servicio = servicio;
    }

    /** 204 si todavía no hay ninguna jornada publicada. */
    @GetMapping("/jornadas/actual")
    public ResponseEntity<JornadaDetalleDto> jornadaActual() {
        return servicio.jornadaActual().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/quinielas")
    public MisQuinielasDto mias(@AuthenticationPrincipal Jwt jwt) {
        return servicio.misQuinielas(usuario(jwt));
    }

    @GetMapping("/quinielas/{id}")
    public QuinielaDetalleDto detalle(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return servicio.detalle(usuario(jwt), "admin".equals(jwt.getClaimAsString("rol")), id);
    }

    @PostMapping("/quinielas")
    @ResponseStatus(HttpStatus.CREATED)
    public QuinielaDetalleDto crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GuardarQuinielaRequest req) {
        return servicio.crear(usuario(jwt), req.idJornada(), req.pronosticos(), req.enviar());
    }

    @PutMapping("/quinielas/{id}")
    public QuinielaDetalleDto guardar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id,
                                      @Valid @RequestBody GuardarQuinielaRequest req) {
        return servicio.guardar(usuario(jwt), id, req.pronosticos(), req.enviar());
    }

    @DeleteMapping("/quinielas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        servicio.eliminar(usuario(jwt), id);
    }

    private static Integer usuario(Jwt jwt) {
        return Integer.valueOf(jwt.getSubject());
    }
}
