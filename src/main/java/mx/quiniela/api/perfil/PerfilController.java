package mx.quiniela.api.perfil;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mx.quiniela.api.perfil.PerfilDtos.EditarPerfilRequest;
import mx.quiniela.api.perfil.PerfilDtos.PerfilDto;

@RestController
@RequestMapping("/api/perfil")
public class PerfilController {

    private final PerfilService servicio;

    public PerfilController(PerfilService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public PerfilDto perfil(@AuthenticationPrincipal Jwt jwt) {
        return servicio.perfil(Integer.valueOf(jwt.getSubject()));
    }

    @PutMapping
    public PerfilDto editar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EditarPerfilRequest req) {
        return servicio.cambiarNombre(Integer.valueOf(jwt.getSubject()), req.nombre());
    }
}
