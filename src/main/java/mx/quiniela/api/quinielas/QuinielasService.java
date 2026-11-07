package mx.quiniela.api.quinielas;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.catalogos.Temporada;
import mx.quiniela.api.catalogos.TemporadaRepository;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.jornadas.EstadoJornada;
import mx.quiniela.api.jornadas.EstadoPartido;
import mx.quiniela.api.jornadas.Jornada;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDetalleDto;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDto;
import mx.quiniela.api.jornadas.JornadaRepository;
import mx.quiniela.api.jornadas.JornadaVistas;
import mx.quiniela.api.jornadas.Partido;
import mx.quiniela.api.pagos.PagosService;
import mx.quiniela.api.quinielas.QuinielaDtos.MisQuinielasDto;
import mx.quiniela.api.quinielas.QuinielaDtos.PronosticoDto;
import mx.quiniela.api.quinielas.QuinielaDtos.PronosticoRequest;
import mx.quiniela.api.quinielas.QuinielaDtos.QuinielaDetalleDto;
import mx.quiniela.api.quinielas.QuinielaDtos.QuinielaDto;
import mx.quiniela.api.usuarios.EstadoUsuario;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/**
 * Quinielas y pronósticos del usuario (pantallas 3 y 6).
 *
 * Una quiniela nace en "borrador" (puede guardarse incompleta); al enviarla con todos los partidos
 * pasa a "pendiente de pago". Los pronósticos se pueden cambiar mientras la jornada acepte pronósticos
 * (abierta y antes del primer partido); después quedan en solo lectura. El trigger de la BD es la red de seguridad.
 */
@Service
public class QuinielasService {

    /** Para que nadie llene la jornada de quinielas por error (o a propósito). */
    static final int MAX_QUINIELAS_POR_JORNADA = 10;

    private final QuinielaRepository quinielas;
    private final PronosticoRepository pronosticos;
    private final JornadaRepository jornadas;
    private final TemporadaRepository temporadas;
    private final UsuarioRepository usuarios;
    private final JornadaVistas vistas;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final PagosService pagos;

    public QuinielasService(QuinielaRepository quinielas, PronosticoRepository pronosticos, JornadaRepository jornadas,
                            TemporadaRepository temporadas, UsuarioRepository usuarios, JornadaVistas vistas,
                            NamedParameterJdbcTemplate jdbc, Clock clock, PagosService pagos) {
        this.pagos = pagos;
        this.quinielas = quinielas;
        this.pronosticos = pronosticos;
        this.jornadas = jornadas;
        this.temporadas = temporadas;
        this.usuarios = usuarios;
        this.vistas = vistas;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ================================================================ consultas

    /** La jornada que ven los usuarios: la abierta; si no hay, la visible más reciente. */
    @Transactional(readOnly = true)
    public Optional<JornadaDetalleDto> jornadaActual() {
        return buscarJornadaActual().map(vistas::detalle);
    }

    @Transactional(readOnly = true)
    public MisQuinielasDto misQuinielas(Integer idUsuario) {
        Usuario u = usuario(idUsuario);
        Instant ahora = clock.instant();
        Optional<Jornada> actual = buscarJornadaActual();

        List<Quiniela> mias = quinielas.findByIdUsuarioOrderByIdJornadaDescNumeroAsc(idUsuario);
        Set<Integer> idsJornada = mias.stream().map(Quiniela::getIdJornada).collect(Collectors.toCollection(HashSet::new));
        actual.ifPresent(j -> idsJornada.add(j.getId()));

        Map<Integer, Jornada> js = jornadas.findAllById(idsJornada).stream()
                .collect(Collectors.toMap(Jornada::getId, Function.identity()));
        Map<Integer, List<Partido>> partidosPorJornada = new HashMap<>();
        Map<Integer, JornadaDto> dtos = new LinkedHashMap<>();
        js.values().stream().sorted((a, b) -> Integer.compare(b.getNumero(), a.getNumero())).forEach(j -> {
            List<Partido> ps = vistas.partidosDe(j);
            partidosPorJornada.put(j.getId(), ps);
            dtos.put(j.getId(), vistas.resumen(j, ps, ahora));
        });

        Map<Integer, List<Pronostico>> prons = pronosticosDe(mias.stream().map(Quiniela::getId).toList());
        Map<Integer, Puntaje> puntajes = puntajes(mias.stream().map(Quiniela::getId).toList());
        boolean aprobado = u.getEstado() == EstadoUsuario.aprobado;

        List<QuinielaDto> lista = mias.stream().map(q -> aDto(q, partidosPorJornada.get(q.getIdJornada()),
                prons.getOrDefault(q.getId(), List.of()), puntajes.get(q.getId()),
                aprobado && dtos.get(q.getIdJornada()).aceptaPronosticos())).toList();

        JornadaDto actualDto = actual.map(j -> dtos.get(j.getId())).orElse(null);
        String motivo = motivoNoPuedeCrear(u, actualDto, actual
                .map(j -> mias.stream().filter(q -> q.getIdJornada().equals(j.getId())).count()).orElse(0L));
        return new MisQuinielasDto(actualDto, motivo == null, motivo, List.copyOf(dtos.values()), lista);
    }

    @Transactional(readOnly = true)
    public QuinielaDetalleDto detalle(Integer idUsuario, boolean esAdmin, Integer idQuiniela) {
        Quiniela q = buscarQuiniela(idQuiniela);
        if (!esAdmin && !q.getIdUsuario().equals(idUsuario)) {
            throw noEncontrada(); // no revelar quinielas ajenas
        }
        return detalleDe(q, aprobado(usuario(q.getIdUsuario())));
    }

    // ================================================================ cambios

    @Transactional
    public QuinielaDetalleDto crear(Integer idUsuario, Integer idJornada, List<PronosticoRequest> lista, boolean enviar) {
        Usuario u = usuario(idUsuario);
        exigirAprobado(u);
        if (idJornada == null) {
            throw ApiException.invalido("FALTA_JORNADA", "Indica la jornada");
        }
        Jornada j = jornadas.findById(idJornada).filter(Jornada::isVisible)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "La jornada no existe"));
        List<Partido> ps = vistas.partidosDe(j);
        exigirAceptaPronosticos(j);
        if (quinielas.countByIdUsuarioAndIdJornada(idUsuario, j.getId()) >= MAX_QUINIELAS_POR_JORNADA) {
            throw conflicto("LIMITE_QUINIELAS", "Llegaste al máximo de " + MAX_QUINIELAS_POR_JORNADA + " quinielas en esta jornada");
        }
        Map<Integer, PronosticoRequest> validos = validarPronosticos(ps, lista, enviar);

        Instant ahora = clock.instant();
        short numero = (short) (quinielas.maxNumero(idUsuario, j.getId()) + 1);
        Quiniela q;
        try {
            q = quinielas.saveAndFlush(new Quiniela(idUsuario, j.getId(), numero, j.getPrecio(), ahora));
        } catch (DataIntegrityViolationException e) {
            throw conflicto("CONFLICTO", "Se creó otra quiniela al mismo tiempo; intenta de nuevo");
        }
        aplicar(q, validos, List.of());
        if (enviar) q.enviar(ahora);
        return detalleDe(q, true);
    }

    @Transactional
    public QuinielaDetalleDto guardar(Integer idUsuario, Integer idQuiniela, List<PronosticoRequest> lista, boolean enviar) {
        Quiniela q = buscarPropia(idUsuario, idQuiniela);
        exigirAprobado(usuario(idUsuario));
        Jornada j = jornadas.findById(q.getIdJornada()).orElseThrow();
        exigirAceptaPronosticos(j);
        // Una quiniela ya enviada no puede quedar incompleta
        Map<Integer, PronosticoRequest> validos = validarPronosticos(vistas.partidosDe(j), lista, enviar || q.enviada());

        boolean cambio = aplicar(q, validos, pronosticos.findByIdQuiniela(q.getId()));
        Instant ahora = clock.instant();
        if (enviar && !q.enviada()) {
            q.enviar(ahora);
            cambio = true;
        }
        if (cambio) q.tocar(ahora);
        return detalleDe(q, true);
    }

    /** Solo mientras no haya pago de por medio (borrador o pendiente de pago) y la jornada acepte pronósticos. */
    @Transactional
    public void eliminar(Integer idUsuario, Integer idQuiniela) {
        Quiniela q = buscarPropia(idUsuario, idQuiniela);
        if (q.getEstado() != EstadoQuiniela.borrador && q.getEstado() != EstadoQuiniela.pendiente_pago) {
            throw conflicto("ESTADO_INVALIDO", "Esta quiniela ya tiene un pago registrado; no se puede eliminar");
        }
        if (quinielas.contarComprobantes(q.getId()) > 0) {
            throw conflicto("TIENE_COMPROBANTE", "Esta quiniela ya tiene un comprobante; no se puede eliminar");
        }
        exigirAceptaPronosticos(jornadas.findById(q.getIdJornada()).orElseThrow());
        pronosticos.deleteByIdQuiniela(q.getId());
        quinielas.delete(q);
    }

    // ================================================================ auxiliares

    /**
     * Valida que cada pronóstico sea de un partido de la jornada (no suspendido) y sin repetir.
     * Si completa=true exige un pronóstico por cada partido que se juega.
     */
    private Map<Integer, PronosticoRequest> validarPronosticos(List<Partido> ps, List<PronosticoRequest> lista, boolean completa) {
        Map<Integer, Partido> jugables = ps.stream().filter(p -> p.getEstado() != EstadoPartido.suspendido)
                .collect(Collectors.toMap(Partido::getId, Function.identity()));
        Map<Integer, PronosticoRequest> porPartido = new LinkedHashMap<>();
        for (PronosticoRequest r : lista) {
            if (!jugables.containsKey(r.idPartido())) {
                throw ApiException.invalido("PARTIDO_INVALIDO", "Uno de los partidos no pertenece a esta jornada");
            }
            if (porPartido.put(r.idPartido(), r) != null) {
                throw ApiException.invalido("PARTIDO_REPETIDO", "Hay un partido repetido en los pronósticos");
            }
        }
        if (completa && porPartido.size() < jugables.size()) {
            int faltan = jugables.size() - porPartido.size();
            throw ApiException.invalido("FALTAN_PRONOSTICOS",
                    "Te " + (faltan == 1 ? "falta 1 partido" : "faltan " + faltan + " partidos") + " por pronosticar");
        }
        return porPartido;
    }

    /** Inserta, actualiza o quita pronósticos para que queden igual a "validos". @return true si algo cambió. */
    private boolean aplicar(Quiniela q, Map<Integer, PronosticoRequest> validos, List<Pronostico> actuales) {
        boolean cambio = false;
        Map<Integer, Pronostico> existentes = actuales.stream()
                .collect(Collectors.toMap(Pronostico::getIdPartido, Function.identity()));
        List<Pronostico> nuevos = new ArrayList<>();
        for (PronosticoRequest r : validos.values()) {
            Pronostico p = existentes.remove(r.idPartido());
            if (p == null) {
                nuevos.add(new Pronostico(q.getId(), r.idPartido(), r.golesLocal(), r.golesVisitante()));
                cambio = true;
            } else {
                cambio |= p.marcar(r.golesLocal(), r.golesVisitante());
            }
        }
        if (!existentes.isEmpty()) {
            pronosticos.deleteAll(existentes.values());
            cambio = true;
        }
        pronosticos.saveAll(nuevos);
        try {
            pronosticos.flush();
        } catch (DataAccessException e) {
            // El trigger de la BD rechazó el cambio: la jornada cerró justo ahora
            throw conflicto("JORNADA_CERRADA", "La jornada ya cerró: no se aceptan más pronósticos");
        }
        return cambio;
    }

    private QuinielaDetalleDto detalleDe(Quiniela q, boolean usuarioAprobado) {
        Jornada j = jornadas.findById(q.getIdJornada()).orElseThrow();
        JornadaDetalleDto jd = vistas.detalle(j);
        List<Pronostico> ps = pronosticos.findByIdQuiniela(q.getId());
        Map<Integer, PuntosPartido> puntos = puntosPorPartido(q.getId());
        List<PronosticoDto> dtos = ps.stream().map(p -> {
            PuntosPartido pp = puntos.get(p.getIdPartido());
            return new PronosticoDto(p.getIdPartido(), p.getGolesLocal(), p.getGolesVisitante(), p.seleccion(),
                    pp == null ? null : pp.puntos(), pp != null && pp.exacto());
        }).toList();
        QuinielaDto dto = aDto(q, vistas.partidosDe(j), ps, puntajes(List.of(q.getId())).get(q.getId()),
                usuarioAprobado && jd.jornada().aceptaPronosticos());
        return new QuinielaDetalleDto(dto, jd, dtos, pagos.pagoDe(q, usuarioAprobado, jd.jornada().aceptaPronosticos()));
    }

    private static QuinielaDto aDto(Quiniela q, List<Partido> ps, List<Pronostico> prons, Puntaje puntaje, boolean editable) {
        int total = (int) ps.stream().filter(p -> p.getEstado() != EstadoPartido.suspendido).count();
        Set<Integer> suspendidos = ps.stream().filter(p -> p.getEstado() == EstadoPartido.suspendido)
                .map(Partido::getId).collect(Collectors.toSet());
        int hechos = (int) prons.stream().filter(p -> !suspendidos.contains(p.getIdPartido())).count();
        return new QuinielaDto(q.getId(), q.getFolio(), q.getNumero(), q.getEstado().name(), q.getMonto(),
                q.getCreadaAt(), q.getEnviadaAt(), q.getActualizadaAt(), q.getIdJornada(), hechos, total,
                puntaje == null ? null : puntaje.puntos(), puntaje == null ? 0 : puntaje.exactos(), editable);
    }

    private record Puntaje(Integer puntos, int exactos) {
    }

    private record PuntosPartido(Integer puntos, boolean exacto) {
    }

    /** Puntos calculados por la vista v_puntos_pronostico (solo cuentan partidos finalizados). */
    private Map<Integer, Puntaje> puntajes(Collection<Integer> idsQuiniela) {
        if (idsQuiniela.isEmpty()) return Map.of();
        Map<Integer, Puntaje> r = new HashMap<>();
        jdbc.query("""
                SELECT id_quiniela, SUM(puntos)::int AS puntos, COUNT(*) FILTER (WHERE es_exacto)::int AS exactos
                FROM v_puntos_pronostico WHERE id_quiniela IN (:ids) GROUP BY id_quiniela""",
                new MapSqlParameterSource("ids", idsQuiniela),
                rs -> {
                    r.put(rs.getInt("id_quiniela"), new Puntaje(rs.getObject("puntos", Integer.class), rs.getInt("exactos")));
                });
        return r;
    }

    private Map<Integer, PuntosPartido> puntosPorPartido(Integer idQuiniela) {
        Map<Integer, PuntosPartido> r = new HashMap<>();
        jdbc.query("SELECT id_partido, puntos, es_exacto FROM v_puntos_pronostico WHERE id_quiniela = :id",
                new MapSqlParameterSource("id", idQuiniela),
                rs -> {
                    r.put(rs.getInt("id_partido"),
                            new PuntosPartido(rs.getObject("puntos", Integer.class), rs.getBoolean("es_exacto")));
                });
        return r;
    }

    private Map<Integer, List<Pronostico>> pronosticosDe(Collection<Integer> idsQuiniela) {
        if (idsQuiniela.isEmpty()) return Map.of();
        return pronosticos.findByIdQuinielaIn(idsQuiniela).stream().collect(Collectors.groupingBy(Pronostico::getIdQuiniela));
    }

    private Optional<Jornada> buscarJornadaActual() {
        Optional<Temporada> t = temporadas.findFirstByActivaTrueOrderByIdDesc();
        if (t.isEmpty()) return Optional.empty();
        List<Jornada> visibles = jornadas.findByIdTemporadaAndVisibleTrueOrderByNumeroDesc(t.get().getId()).stream()
                .filter(j -> j.getEstado() != EstadoJornada.borrador).toList();
        return visibles.stream().filter(j -> j.getEstado() == EstadoJornada.abierta).findFirst()
                .or(() -> visibles.stream().findFirst());
    }

    private static String motivoNoPuedeCrear(Usuario u, JornadaDto actual, long yaTengo) {
        if (!aprobado(u)) return "Tu cuenta está pendiente de aprobación por el administrador";
        if (actual == null || !actual.aceptaPronosticos()) return "No hay una jornada abierta en este momento";
        if (yaTengo >= MAX_QUINIELAS_POR_JORNADA) return "Llegaste al máximo de " + MAX_QUINIELAS_POR_JORNADA + " quinielas en esta jornada";
        return null;
    }

    private void exigirAceptaPronosticos(Jornada j) {
        if (!vistas.aceptaPronosticos(j)) {
            throw conflicto("JORNADA_CERRADA", "La jornada ya cerró: no se aceptan más pronósticos");
        }
    }

    private static void exigirAprobado(Usuario u) {
        if (!aprobado(u)) {
            throw ApiException.prohibido("USUARIO_NO_APROBADO", "Tu cuenta está pendiente de aprobación por el administrador");
        }
    }

    private static boolean aprobado(Usuario u) {
        return u.getEstado() == EstadoUsuario.aprobado;
    }

    private Usuario usuario(Integer id) {
        return usuarios.findById(id).filter(u -> u.getEliminadoAt() == null)
                .orElseThrow(() -> ApiException.noAutorizado("SESION_INVALIDA", "Tu sesión ya no es válida"));
    }

    private Quiniela buscarQuiniela(Integer id) {
        return quinielas.findById(id).orElseThrow(QuinielasService::noEncontrada);
    }

    private Quiniela buscarPropia(Integer idUsuario, Integer idQuiniela) {
        Quiniela q = buscarQuiniela(idQuiniela);
        if (!q.getIdUsuario().equals(idUsuario)) throw noEncontrada();
        return q;
    }

    private static ApiException noEncontrada() {
        return new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "La quiniela no existe");
    }

    private static ApiException conflicto(String codigo, String mensaje) {
        return new ApiException(HttpStatus.CONFLICT, codigo, mensaje);
    }
}
