package mx.quiniela.api.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.auth.SesionRepository;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.correo.CorreoService;
import mx.quiniela.api.perfil.PerfilDtos.PerfilDto;
import mx.quiniela.api.perfil.PerfilService;
import mx.quiniela.api.usuarios.EstadoUsuario;
import mx.quiniela.api.usuarios.Rol;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/** Gestión de usuarios por el administrador: aprobar, bloquear y desbloquear. Todo queda en la bitácora. */
@Service
public class AdminUsuariosService {

    private final UsuarioRepository usuarios;
    private final SesionRepository sesiones;
    private final CorreoService correo;
    private final Bitacora bitacora;
    private final Clock clock;

    private final PerfilService perfiles;

    public AdminUsuariosService(UsuarioRepository usuarios, SesionRepository sesiones, CorreoService correo,
                                Bitacora bitacora, Clock clock, PerfilService perfiles) {
        this.perfiles = perfiles;
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.correo = correo;
        this.bitacora = bitacora;
        this.clock = clock;
    }

    /**
     * Pendientes primero por antigüedad (el que lleva más tiempo esperando arriba); el resto, los más nuevos primero.
     * q busca en username, nombre y correo.
     */
    @Transactional(readOnly = true)
    public Page<Usuario> listar(EstadoUsuario estado, String q, int pagina, int tamano) {
        int size = Math.clamp(tamano, 1, 100);
        int page = Math.max(pagina, 0);
        // SQL nativo: el orden va por nombre de columna
        Sort orden = estado == EstadoUsuario.pendiente ? Sort.by("id_usuario") : Sort.by(Sort.Direction.DESC, "id_usuario");
        String busqueda = q == null || q.isBlank() ? null : q.trim();
        return usuarios.buscarAdmin(estado == null ? null : estado.name(), busqueda, PageRequest.of(page, size, orden));
    }

    /** Cuántos hay en cada estado (para las pestañas). */
    @Transactional(readOnly = true)
    public Map<String, Long> conteos() {
        Map<String, Long> r = new java.util.LinkedHashMap<>();
        for (EstadoUsuario e : EstadoUsuario.values()) r.put(e.name(), 0L);
        for (Object[] fila : usuarios.contarPorEstado()) r.put((String) fila[0], ((Number) fila[1]).longValue());
        return r;
    }

    @Transactional(readOnly = true)
    public PerfilDto detalle(Integer idUsuario) {
        return perfiles.perfilDe(buscar(idUsuario));
    }

    /** El admin corrige nombre y correo (el username no cambia: es el nombre público en el ranking). */
    @Transactional
    public PerfilDto editar(Integer idAdmin, Integer idUsuario, String nombre, String correoNuevo) {
        Usuario u = buscar(idUsuario);
        String n = nombre.trim();
        String c = correoNuevo.trim();
        if (!c.equalsIgnoreCase(u.getCorreo()) && usuarios.correoOcupadoPorOtro(c, u.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CORREO_EN_USO", "Ese correo ya está registrado en otra cuenta");
        }
        Map<String, Object> antes = Map.of("nombre", u.getNombre(), "correo", u.getCorreo());
        u.setNombre(n);
        u.setCorreo(c);
        usuarios.flush();
        if (!antes.get("nombre").equals(n) || !antes.get("correo").toString().equalsIgnoreCase(c)) {
            bitacora.registrar(idAdmin, "EDITAR_USUARIO", "usuarios", u.getId(), antes, Map.of("nombre", n, "correo", c));
        }
        return perfiles.perfilDe(u);
    }

    /**
     * Borrado lógico: ya no puede entrar y desaparece de la lista; sus quinielas, pagos y lugares en el ranking
     * se conservan (son historial de la quiniela). El username y el correo no se liberan.
     */
    @Transactional
    public void eliminar(Integer idAdmin, Integer idUsuario) {
        Usuario u = buscar(idUsuario);
        if (u.getId().equals(idAdmin)) {
            throw ApiException.invalido("NO_PERMITIDO", "No puedes eliminar tu propia cuenta");
        }
        if (u.getRol() == Rol.admin) {
            throw ApiException.invalido("NO_PERMITIDO", "No se puede eliminar a otro administrador");
        }
        Instant ahora = clock.instant();
        u.eliminar(ahora);
        sesiones.revocarTodas(u.getId(), ahora);
        bitacora.registrar(idAdmin, "ELIMINAR_USUARIO", "usuarios", u.getId(),
                Map.of("username", u.getUsername(), "estado", u.getEstado().name()), Map.of("eliminado", true));
    }

    @Transactional
    public Usuario aprobar(Integer idAdmin, Integer idUsuario) {
        Usuario u = buscar(idUsuario);
        if (u.getEstado() != EstadoUsuario.pendiente) {
            throw new ApiException(HttpStatus.CONFLICT, "ESTADO_INVALIDO",
                    "Solo se pueden aprobar usuarios pendientes (este está " + u.getEstado() + ")");
        }
        EstadoUsuario antes = u.getEstado();
        u.aprobar(idAdmin, clock.instant());
        bitacora.registrar(idAdmin, "APROBAR_USUARIO", "usuarios", u.getId(),
                Map.of("estado", antes.name()), Map.of("estado", u.getEstado().name()));
        correo.enviarCuentaAprobada(u.getCorreo(), u.getNombre());
        return u;
    }

    /** Bloquea la cuenta y cierra todas sus sesiones (el token de acceso vigente expira en máx. 15 min). */
    @Transactional
    public Usuario bloquear(Integer idAdmin, Integer idUsuario) {
        Usuario u = buscar(idUsuario);
        if (u.getId().equals(idAdmin)) {
            throw ApiException.invalido("NO_PERMITIDO", "No puedes bloquear tu propia cuenta");
        }
        if (u.getRol() == Rol.admin) {
            throw ApiException.invalido("NO_PERMITIDO", "No se puede bloquear a otro administrador");
        }
        if (u.getEstado() == EstadoUsuario.bloqueado) {
            return u;
        }
        EstadoUsuario antes = u.getEstado();
        Instant ahora = clock.instant();
        u.setEstado(EstadoUsuario.bloqueado);
        sesiones.revocarTodas(u.getId(), ahora);
        bitacora.registrar(idAdmin, "BLOQUEAR_USUARIO", "usuarios", u.getId(),
                Map.of("estado", antes.name()), Map.of("estado", u.getEstado().name()));
        return u;
    }

    /** Un bloqueado vuelve como aprobado (ya había sido revisado). */
    @Transactional
    public Usuario desbloquear(Integer idAdmin, Integer idUsuario) {
        Usuario u = buscar(idUsuario);
        if (u.getEstado() != EstadoUsuario.bloqueado) {
            throw new ApiException(HttpStatus.CONFLICT, "ESTADO_INVALIDO", "El usuario no está bloqueado");
        }
        u.aprobar(idAdmin, clock.instant());
        u.reiniciarIntentosLogin();
        bitacora.registrar(idAdmin, "DESBLOQUEAR_USUARIO", "usuarios", u.getId(),
                Map.of("estado", EstadoUsuario.bloqueado.name()), Map.of("estado", u.getEstado().name()));
        return u;
    }

    private Usuario buscar(Integer idUsuario) {
        return usuarios.findById(idUsuario)
                .filter(u -> u.getEliminadoAt() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "El usuario no existe"));
    }
}
