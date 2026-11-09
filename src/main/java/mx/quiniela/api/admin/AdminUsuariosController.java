package mx.quiniela.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mx.quiniela.api.perfil.PerfilDtos.EditarUsuarioRequest;
import mx.quiniela.api.perfil.PerfilDtos.PerfilDto;
import mx.quiniela.api.usuarios.EstadoUsuario;
import mx.quiniela.api.usuarios.Usuario;

@RestController
@RequestMapping("/api/admin/usuarios")
@PreAuthorize("hasRole('admin')")
public class AdminUsuariosController {

    private final AdminUsuariosService servicio;

    public AdminUsuariosController(AdminUsuariosService servicio) {
        this.servicio = servicio;
    }

    public record UsuarioAdminDto(Integer id, String username, String nombre, String correo, String rol,
                                  String estado, Instant fechaRegistro, Instant aprobadoAt, Instant ultimoLogin,
                                  String avatar) {
        static UsuarioAdminDto de(Usuario u) {
            return new UsuarioAdminDto(u.getId(), u.getUsername(), u.getNombre(), u.getCorreo(), u.getRol().name(),
                    u.getEstado().name(), u.getFechaRegistro(), u.getAprobadoAt(), u.getUltimoLogin(), u.getAvatarUrl());
        }
    }

    public record PaginaDto<T>(List<T> elementos, int pagina, int tamano, long total, int totalPaginas) {
    }

    /** GET /api/admin/usuarios?estado=pendiente&q=texto&pagina=0&tamano=20 (estado y q opcionales). */
    @GetMapping
    public PaginaDto<UsuarioAdminDto> listar(@RequestParam(required = false) EstadoUsuario estado,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int pagina,
                                             @RequestParam(defaultValue = "20") int tamano) {
        Page<Usuario> p = servicio.listar(estado, q, pagina, tamano);
        return new PaginaDto<>(p.map(UsuarioAdminDto::de).getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/conteos")
    public Map<String, Long> conteos() {
        return servicio.conteos();
    }

    /** Pantalla 2 (vista admin): datos, estadísticas e historial. */
    @GetMapping("/{id}")
    public PerfilDto detalle(@PathVariable Integer id) {
        return servicio.detalle(id);
    }

    @PutMapping("/{id}")
    public PerfilDto editar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id,
                            @Valid @RequestBody EditarUsuarioRequest req) {
        return servicio.editar(idAdmin(jwt), id, req.nombre(), req.correo());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        servicio.eliminar(idAdmin(jwt), id);
    }

    @PutMapping("/{id}/aprobar")
    public UsuarioAdminDto aprobar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return UsuarioAdminDto.de(servicio.aprobar(idAdmin(jwt), id));
    }

    @PutMapping("/{id}/bloquear")
    public UsuarioAdminDto bloquear(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return UsuarioAdminDto.de(servicio.bloquear(idAdmin(jwt), id));
    }

    @PutMapping("/{id}/desbloquear")
    public UsuarioAdminDto desbloquear(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return UsuarioAdminDto.de(servicio.desbloquear(idAdmin(jwt), id));
    }

    private static Integer idAdmin(Jwt jwt) {
        return Integer.valueOf(jwt.getSubject());
    }
}
