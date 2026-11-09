package mx.quiniela.api.torneos;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.admin.Bitacora;
import mx.quiniela.api.catalogos.EstadoTorneo;
import mx.quiniela.api.catalogos.Temporada;
import mx.quiniela.api.catalogos.TemporadaRepository;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.torneos.TorneoDtos.TorneoDto;

/**
 * Torneos (tabla temporadas). Ciclo: proximo → en_curso → finalizado.
 *  - Solo uno puede estar en curso: es el que ve la gente (jornada actual, resultados).
 *  - En un torneo próximo se preparan jornadas, pero no se pueden abrir hasta iniciarlo.
 *  - Un torneo finalizado ya no acepta jornadas nuevas. Para finalizarlo, todas sus jornadas deben estar finalizadas.
 */
@Service
public class TorneosService {

    private final TemporadaRepository temporadas;
    private final NamedParameterJdbcTemplate jdbc;
    private final Bitacora bitacora;

    public TorneosService(TemporadaRepository temporadas, NamedParameterJdbcTemplate jdbc, Bitacora bitacora) {
        this.temporadas = temporadas;
        this.jdbc = jdbc;
        this.bitacora = bitacora;
    }

    @Transactional(readOnly = true)
    public List<TorneoDto> listar() {
        return jdbc.query(SELECT + " ORDER BY (t.estado = 'en_curso') DESC, t.id_temporada DESC",
                new MapSqlParameterSource(), (rs, i) -> fila(rs));
    }

    @Transactional
    public TorneoDto crear(Integer idAdmin, String nombre, LocalDate inicio, LocalDate fin) {
        String n = nombre.trim();
        validarFechas(inicio, fin);
        if (temporadas.existsByNombreIgnoreCase(n)) throw nombreEnUso(n);
        Temporada t = guardar(new Temporada(n, inicio, fin));
        bitacora.registrar(idAdmin, "CREAR_TORNEO", "temporadas", t.getId(), null, Map.of("nombre", n));
        return dto(t.getId());
    }

    @Transactional
    public TorneoDto editar(Integer idAdmin, Integer id, String nombre, LocalDate inicio, LocalDate fin) {
        Temporada t = buscar(id);
        String n = nombre.trim();
        validarFechas(inicio, fin);
        if (temporadas.existsByNombreIgnoreCaseAndIdNot(n, id)) throw nombreEnUso(n);
        String antes = t.getNombre();
        t.editar(n, inicio, fin);
        guardar(t);
        if (!antes.equals(n)) {
            bitacora.registrar(idAdmin, "EDITAR_TORNEO", "temporadas", id, Map.of("nombre", antes), Map.of("nombre", n));
        }
        return dto(id);
    }

    /** Lo pone en curso. Si ya hay otro en curso, hay que finalizarlo primero. */
    @Transactional
    public TorneoDto iniciar(Integer idAdmin, Integer id) {
        Temporada t = buscar(id);
        if (t.getEstado() != EstadoTorneo.proximo) {
            throw conflicto("ESTADO_INVALIDO", t.enCurso() ? "El torneo ya está en curso" : "El torneo ya está finalizado");
        }
        temporadas.enCurso().ifPresent(otro -> {
            throw conflicto("OTRO_EN_CURSO", "\"" + otro.getNombre() + "\" sigue en curso. Finalízalo antes de iniciar este");
        });
        t.setEstado(EstadoTorneo.en_curso);
        guardar(t);
        bitacora.registrar(idAdmin, "INICIAR_TORNEO", "temporadas", id, Map.of("estado", "proximo"), Map.of("estado", "en_curso"));
        return dto(id);
    }

    /** Lo cierra: deja de aceptar jornadas. Exige que todas sus jornadas estén finalizadas. */
    @Transactional
    public TorneoDto finalizar(Integer idAdmin, Integer id) {
        Temporada t = buscar(id);
        if (t.finalizado()) throw conflicto("ESTADO_INVALIDO", "El torneo ya está finalizado");
        Integer pendientes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jornadas WHERE id_temporada = :t AND estado <> 'finalizada'",
                new MapSqlParameterSource("t", id), Integer.class);
        if (pendientes != null && pendientes > 0) {
            throw conflicto("JORNADAS_PENDIENTES", "El torneo tiene " + pendientes
                    + (pendientes == 1 ? " jornada sin finalizar" : " jornadas sin finalizar")
                    + ". Finalízalas o elimina las que estén en borrador");
        }
        String antes = t.getEstado().name();
        t.setEstado(EstadoTorneo.finalizado);
        guardar(t);
        bitacora.registrar(idAdmin, "FINALIZAR_TORNEO", "temporadas", id, Map.of("estado", antes), Map.of("estado", "finalizado"));
        return dto(id);
    }

    /** Solo un torneo próximo y sin jornadas (para corregir uno creado por error). */
    @Transactional
    public void eliminar(Integer idAdmin, Integer id) {
        Temporada t = buscar(id);
        if (t.getEstado() != EstadoTorneo.proximo) {
            throw conflicto("ESTADO_INVALIDO", "Solo se puede eliminar un torneo que no ha iniciado");
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM jornadas WHERE id_temporada = :t",
                new MapSqlParameterSource("t", id), Integer.class);
        if (n != null && n > 0) throw conflicto("TIENE_JORNADAS", "El torneo ya tiene jornadas; no se puede eliminar");
        temporadas.delete(t);
        temporadas.flush();
        bitacora.registrar(idAdmin, "ELIMINAR_TORNEO", "temporadas", id, Map.of("nombre", t.getNombre()), null);
    }

    // ---------------------------------------------------------------- auxiliares

    private Temporada guardar(Temporada t) {
        try {
            return temporadas.saveAndFlush(t);
        } catch (DataIntegrityViolationException e) {
            // Respaldo de los índices únicos (nombre / un solo torneo en curso)
            throw conflicto("CONFLICTO", "No se pudo guardar el torneo: revisa que el nombre no exista y que no haya otro en curso");
        }
    }

    private Temporada buscar(Integer id) {
        return temporadas.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "El torneo no existe"));
    }

    private TorneoDto dto(Integer id) {
        return jdbc.queryForObject(SELECT + " WHERE t.id_temporada = :id", new MapSqlParameterSource("id", id),
                (rs, i) -> fila(rs));
    }

    private static void validarFechas(LocalDate inicio, LocalDate fin) {
        if (inicio != null && fin != null && fin.isBefore(inicio)) {
            throw ApiException.invalido("FECHAS_INVALIDAS", "La fecha de fin no puede ser antes del inicio");
        }
    }

    private static ApiException nombreEnUso(String n) {
        return conflicto("NOMBRE_EN_USO", "Ya existe un torneo llamado \"" + n + "\"");
    }

    private static ApiException conflicto(String codigo, String mensaje) {
        return new ApiException(HttpStatus.CONFLICT, codigo, mensaje);
    }

    private static final String SELECT = """
            SELECT t.id_temporada, t.nombre, t.estado, t.fecha_inicio, t.fecha_fin,
                   (SELECT COUNT(*) FROM jornadas j WHERE j.id_temporada = t.id_temporada) AS total,
                   (SELECT COUNT(*) FROM jornadas j WHERE j.id_temporada = t.id_temporada
                      AND j.estado = 'finalizada') AS finalizadas
            FROM temporadas t
            """;

    private static TorneoDto fila(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TorneoDto(rs.getInt("id_temporada"), rs.getString("nombre"), rs.getString("estado"),
                rs.getObject("fecha_inicio", LocalDate.class), rs.getObject("fecha_fin", LocalDate.class),
                rs.getInt("total"), rs.getInt("finalizadas"));
    }
}
