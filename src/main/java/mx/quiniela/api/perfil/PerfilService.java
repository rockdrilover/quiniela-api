package mx.quiniela.api.perfil;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.perfil.PerfilDtos.DatosUsuarioDto;
import mx.quiniela.api.perfil.PerfilDtos.EstadisticasDto;
import mx.quiniela.api.perfil.PerfilDtos.HistorialDto;
import mx.quiniela.api.perfil.PerfilDtos.PerfilDto;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/** Pantalla 2: datos del jugador, estadísticas personales e historial de participación. */
@Service
public class PerfilService {

    private static final int MAX_HISTORIAL = 20;

    private final UsuarioRepository usuarios;
    private final NamedParameterJdbcTemplate jdbc;

    public PerfilService(UsuarioRepository usuarios, NamedParameterJdbcTemplate jdbc) {
        this.usuarios = usuarios;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PerfilDto perfil(Integer idUsuario) {
        return perfilDe(buscar(idUsuario));
    }

    /** El usuario solo puede cambiar su nombre; el username es fijo (es su nombre público en el ranking). */
    @Transactional
    public PerfilDto cambiarNombre(Integer idUsuario, String nombre) {
        Usuario u = buscar(idUsuario);
        u.setNombre(nombre.trim());
        usuarios.flush();
        return perfilDe(u);
    }

    public PerfilDto perfilDe(Usuario u) {
        MapSqlParameterSource p = new MapSqlParameterSource("u", u.getId());
        BigDecimal premios = jdbc.queryForObject(
                "SELECT COALESCE(SUM(g.premio), 0) FROM v_ganadores_jornada g JOIN jornadas j USING (id_jornada) "
                        + "WHERE g.id_usuario = :u AND j.estado = 'finalizada'", p, BigDecimal.class);
        EstadisticasDto stats = jdbc.queryForObject("""
                SELECT quinielas_jugadas, puntos_totales, exactos_totales, quinielas_ganadas
                FROM v_estadisticas_usuario WHERE id_usuario = :u""", p,
                (rs, i) -> new EstadisticasDto(rs.getInt("quinielas_jugadas"), rs.getInt("puntos_totales"),
                        rs.getInt("exactos_totales"), rs.getInt("quinielas_ganadas"), premios));

        List<HistorialDto> historial = jdbc.query("""
                SELECT r.id_quiniela, r.folio, r.numero_en_jornada, r.id_jornada, j.nombre AS jornada,
                       j.estado::text AS estado_jornada, r.posicion, r.puntos, r.exactos,
                       (SELECT COUNT(*) FROM v_ranking_jornada x WHERE x.id_jornada = r.id_jornada) AS participantes,
                       g.premio
                FROM v_ranking_jornada r
                JOIN jornadas j ON j.id_jornada = r.id_jornada
                LEFT JOIN v_ganadores_jornada g ON g.id_quiniela = r.id_quiniela AND j.estado = 'finalizada'
                WHERE r.id_usuario = :u
                ORDER BY j.numero_jornada DESC, r.numero_en_jornada
                LIMIT %d""".formatted(MAX_HISTORIAL), p,
                (rs, i) -> new HistorialDto(rs.getInt("id_quiniela"), rs.getInt("folio"), rs.getInt("numero_en_jornada"),
                        rs.getInt("id_jornada"), rs.getString("jornada"), rs.getString("estado_jornada"),
                        rs.getInt("posicion"), rs.getInt("participantes"), rs.getInt("puntos"), rs.getInt("exactos"),
                        rs.getBigDecimal("premio") != null, rs.getBigDecimal("premio")));

        DatosUsuarioDto datos = new DatosUsuarioDto(u.getId(), u.getUsername(), u.getNombre(), u.getCorreo(),
                u.getRol().name(), u.getEstado().name(), u.getFechaRegistro(), u.getUltimoLogin());
        return new PerfilDto(datos, stats, historial);
    }

    private Usuario buscar(Integer id) {
        return usuarios.findById(id).filter(u -> u.getEliminadoAt() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "El usuario no existe"));
    }
}
