package mx.quiniela.api.ranking;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.catalogos.TemporadaRepository;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.jornadas.EstadoJornada;
import mx.quiniela.api.jornadas.Jornada;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDetalleDto;
import mx.quiniela.api.jornadas.JornadaRepository;
import mx.quiniela.api.jornadas.JornadaVistas;
import mx.quiniela.api.quinielas.QuinielasService;
import mx.quiniela.api.ranking.RankingDtos.BolsaDto;
import mx.quiniela.api.ranking.RankingDtos.CeldaDto;
import mx.quiniela.api.ranking.RankingDtos.FilaDto;
import mx.quiniela.api.ranking.RankingDtos.JornadaOpcionDto;
import mx.quiniela.api.ranking.RankingDtos.JornadasRankingDto;
import mx.quiniela.api.ranking.RankingDtos.RankingDto;

/**
 * Pantalla 9: tabla de posiciones de una jornada. Solo cuentan las quinielas con pago validado
 * ("participando"); los puntos y posiciones salen de las vistas de la BD (v_ranking_jornada, etc.).
 */
@Service
public class RankingService {

    private final JornadaRepository jornadas;
    private final TemporadaRepository temporadas;
    private final JornadaVistas vistas;
    private final QuinielasService quinielas;
    private final NamedParameterJdbcTemplate jdbc;

    public RankingService(JornadaRepository jornadas, TemporadaRepository temporadas, JornadaVistas vistas,
                          QuinielasService quinielas, NamedParameterJdbcTemplate jdbc) {
        this.jornadas = jornadas;
        this.temporadas = temporadas;
        this.vistas = vistas;
        this.quinielas = quinielas;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public JornadasRankingDto jornadas() {
        List<JornadaOpcionDto> lista = temporadas.findFirstByActivaTrueOrderByIdDesc()
                .map(t -> jornadas.findByIdTemporadaAndVisibleTrueOrderByNumeroDesc(t.getId()).stream()
                        .filter(j -> j.getEstado() != EstadoJornada.borrador)
                        .map(j -> {
                            JornadaDetalleDto d = vistas.detalle(j);
                            return new JornadaOpcionDto(j.getId(), j.getNumero(), j.getNombre(), d.jornada().estadoVisual());
                        }).toList())
                .orElse(List.of());
        Integer actual = quinielas.jornadaActual().map(d -> d.jornada().id()).orElse(null);
        return new JornadasRankingDto(actual, lista);
    }

    @Transactional(readOnly = true)
    public Optional<RankingDto> ranking(Integer idUsuario, boolean esAdmin, Integer idJornada) {
        Jornada j;
        if (idJornada == null) {
            Optional<Integer> actual = quinielas.jornadaActual().map(d -> d.jornada().id());
            if (actual.isEmpty()) return Optional.empty();
            j = jornadas.findById(actual.get()).orElseThrow();
        } else {
            j = jornadas.findById(idJornada)
                    .filter(x -> esAdmin || (x.isVisible() && x.getEstado() != EstadoJornada.borrador))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "La jornada no existe"));
        }

        JornadaDetalleDto detalle = vistas.detalle(j);
        boolean visibles = !detalle.jornada().aceptaPronosticos();
        boolean finalizada = j.getEstado() == EstadoJornada.finalizada;
        MapSqlParameterSource p = new MapSqlParameterSource("j", j.getId());

        BolsaDto bolsa = jdbc.queryForObject(
                "SELECT participantes, recaudado, bolsa FROM v_bolsa_jornada WHERE id_jornada = :j", p,
                (rs, i) -> new BolsaDto(rs.getInt("participantes"), rs.getBigDecimal("recaudado"), rs.getBigDecimal("bolsa")));

        Map<Integer, BigDecimal> premios = new HashMap<>();
        if (finalizada) {
            jdbc.query("SELECT id_quiniela, premio FROM v_ganadores_jornada WHERE id_jornada = :j", p,
                    rs -> {
                        premios.put(rs.getInt("id_quiniela"), rs.getBigDecimal("premio"));
                    });
        }

        Map<Integer, List<CeldaDto>> celdas = new HashMap<>();
        jdbc.query("""
                SELECT vp.id_quiniela, vp.id_partido, vp.goles_local, vp.goles_visitante, vp.seleccion::text AS seleccion,
                       vp.puntos, vp.es_exacto, q.id_usuario
                FROM v_puntos_pronostico vp JOIN quinielas q ON q.id_quiniela = vp.id_quiniela
                WHERE q.id_jornada = :j AND q.estado = 'participando'
                """, p, rs -> {
                    boolean propia = idUsuario.equals(rs.getInt("id_usuario"));
                    if (!visibles && !propia) return;
                    celdas.computeIfAbsent(rs.getInt("id_quiniela"), k -> new ArrayList<>()).add(new CeldaDto(
                            rs.getInt("id_partido"), rs.getInt("goles_local"), rs.getInt("goles_visitante"),
                            rs.getString("seleccion"), rs.getObject("puntos", Integer.class), rs.getBoolean("es_exacto")));
                });

        List<FilaDto> filas = jdbc.query("""
                SELECT r.posicion, r.id_quiniela, r.folio, r.numero_en_jornada, r.id_usuario, r.username::text AS username,
                       r.puntos, r.exactos,
                       COUNT(*) OVER (PARTITION BY r.id_usuario) > 1 AS varias
                FROM v_ranking_jornada r
                WHERE r.id_jornada = :j
                ORDER BY r.posicion, r.username, r.numero_en_jornada
                """, p, (rs, i) -> {
                    int idQ = rs.getInt("id_quiniela");
                    int pos = rs.getInt("posicion");
                    return new FilaDto(pos, idQ, rs.getInt("folio"), rs.getInt("numero_en_jornada"),
                            rs.getInt("id_usuario"), rs.getString("username"), rs.getBoolean("varias"),
                            rs.getInt("puntos"), rs.getInt("exactos"), idUsuario.equals(rs.getInt("id_usuario")),
                            premios.containsKey(idQ), premios.get(idQ), celdas.getOrDefault(idQ, List.of()));
                });

        return Optional.of(new RankingDto(detalle.jornada(), detalle.partidos(), bolsa, visibles, filas));
    }
}
