package mx.quiniela.api.jornadas;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.admin.Bitacora;
import mx.quiniela.api.catalogos.Equipo;
import mx.quiniela.api.catalogos.EquipoRepository;
import mx.quiniela.api.catalogos.Temporada;
import mx.quiniela.api.catalogos.TemporadaRepository;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.jornadas.JornadaDtos.EstadoVisual;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDetalleDto;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDto;
import mx.quiniela.api.jornadas.JornadaDtos.ListaJornadasDto;

/**
 * Gestión de jornadas y partidos por el administrador (pantallas 4 y 5).
 *
 * Ciclo de vida de una jornada:
 *   borrador  → se arman los partidos (solo aquí se agregan, editan o quitan)
 *   abierta   → los usuarios capturan pronósticos hasta que empieza el primer partido (solo una abierta a la vez)
 *   cerrada   → ya no acepta pronósticos; el admin captura marcadores
 *   finalizada→ todos los partidos tienen marcador (o están suspendidos); el ranking queda definitivo
 * Los puntos NO se guardan: las vistas de la BD los recalculan al instante con cada marcador.
 */
@Service
public class JornadasAdminService {

    private static final BigDecimal PCT_PREMIO_DEFAULT = new BigDecimal("80");

    private final JornadaRepository jornadas;
    private final PartidoRepository partidos;
    private final EquipoRepository equipos;
    private final TemporadaRepository temporadas;
    private final Bitacora bitacora;
    private final Clock clock;
    private final JornadaVistas vistas;

    public JornadasAdminService(JornadaRepository jornadas, PartidoRepository partidos, EquipoRepository equipos,
                                TemporadaRepository temporadas, Bitacora bitacora, Clock clock,
                                JornadaVistas vistas) {
        this.vistas = vistas;
        this.jornadas = jornadas;
        this.partidos = partidos;
        this.equipos = equipos;
        this.temporadas = temporadas;
        this.bitacora = bitacora;
        this.clock = clock;
    }

    // ================================================================ consultas

    @Transactional(readOnly = true)
    public ListaJornadasDto listar() {
        Temporada t = temporadaActiva();
        List<Jornada> lista = jornadas.findByIdTemporadaOrderByNumeroDesc(t.getId());
        Map<Integer, List<Partido>> porJornada = partidos.findByIdJornadaIn(lista.stream().map(Jornada::getId).toList())
                .stream().collect(Collectors.groupingBy(Partido::getIdJornada));
        Instant ahora = clock.instant();
        List<JornadaDto> dtos = lista.stream()
                .map(j -> aDto(j, porJornada.getOrDefault(j.getId(), List.of()), ahora))
                .toList();
        return new ListaJornadasDto(t.getId(), t.getNombre(), dtos);
    }

    @Transactional(readOnly = true)
    public JornadaDetalleDto detalle(Integer idJornada) {
        return detalleDe(buscarJornada(idJornada));
    }

    // ================================================================ jornadas

    @Transactional
    public JornadaDetalleDto crear(Integer idAdmin, String nombre, BigDecimal precio, BigDecimal pctPremio) {
        Temporada t = temporadaActiva();
        int numero = jornadas.maxNumero(t.getId()) + 1;
        String n = (nombre == null || nombre.isBlank()) ? "Jornada " + numero : nombre.trim();
        Jornada j = jornadas.saveAndFlush(new Jornada(t.getId(), numero, n, precio,
                pctPremio != null ? pctPremio : PCT_PREMIO_DEFAULT));
        bitacora.registrar(idAdmin, "CREAR_JORNADA", "jornadas", j.getId(), null,
                Map.of("numero", numero, "nombre", n, "precio", precio));
        return detalleDe(j);
    }

    /** Nombre siempre; precio y % de premio solo en borrador (después ya hay quinielas con ese precio). */
    @Transactional
    public JornadaDetalleDto editar(Integer idJornada, String nombre, BigDecimal precio, BigDecimal pctPremio) {
        Jornada j = buscarJornada(idJornada);
        if (nombre != null && !nombre.isBlank()) j.setNombre(nombre.trim());
        boolean cambiaDinero = (precio != null && precio.compareTo(j.getPrecio()) != 0)
                || (pctPremio != null && pctPremio.compareTo(j.getPctPremio()) != 0);
        if (cambiaDinero) {
            exigirBorrador(j, "El precio y el % de premio solo se pueden cambiar mientras la jornada está en borrador");
            if (precio != null) j.setPrecio(precio);
            if (pctPremio != null) j.setPctPremio(pctPremio);
        }
        return detalleDe(j);
    }

    @Transactional
    public void eliminar(Integer idAdmin, Integer idJornada) {
        Jornada j = buscarJornada(idJornada);
        exigirBorrador(j, "Solo se pueden eliminar jornadas en borrador");
        if (jornadas.contarQuinielas(j.getId()) > 0) {
            throw conflicto("TIENE_QUINIELAS", "La jornada ya tiene quinielas; no se puede eliminar");
        }
        partidos.deleteByIdJornada(j.getId());
        jornadas.delete(j);
        bitacora.registrar(idAdmin, "ELIMINAR_JORNADA", "jornadas", idJornada,
                Map.of("nombre", j.getNombre()), null);
    }

    /** Abre la jornada a pronósticos. También sirve para reabrir una cerrada si el primer partido no ha empezado. */
    @Transactional
    public JornadaDetalleDto abrir(Integer idAdmin, Integer idJornada) {
        Jornada j = buscarJornada(idJornada);
        if (j.getEstado() != EstadoJornada.borrador && j.getEstado() != EstadoJornada.cerrada) {
            throw conflicto("ESTADO_INVALIDO", "Solo se puede abrir una jornada en borrador o cerrada");
        }
        List<Partido> ps = partidosDe(j);
        if (ps.isEmpty()) {
            throw conflicto("SIN_PARTIDOS", "Agrega al menos un partido antes de abrir la jornada");
        }
        Instant cierre = fechaCierre(ps);
        if (cierre == null || !cierre.isAfter(clock.instant())) {
            throw conflicto("YA_EMPEZO", "El primer partido ya empezó; la jornada no puede abrirse a pronósticos");
        }
        if (jornadas.contarOtrasAbiertas(j.getId()) > 0) {
            throw conflicto("OTRA_ABIERTA", "Ya hay otra jornada abierta. Ciérrala antes de abrir esta");
        }
        String antes = j.getEstado().name();
        j.setEstado(EstadoJornada.abierta);
        j.setVisible(true);
        try {
            jornadas.flush();
        } catch (DataIntegrityViolationException e) {
            throw conflicto("OTRA_ABIERTA", "Ya hay otra jornada abierta. Ciérrala antes de abrir esta");
        }
        bitacora.registrar(idAdmin, "ABRIR_JORNADA", "jornadas", j.getId(), Map.of("estado", antes), Map.of("estado", "abierta"));
        return detalleDe(j);
    }

    @Transactional
    public JornadaDetalleDto cerrar(Integer idAdmin, Integer idJornada) {
        Jornada j = buscarJornada(idJornada);
        if (j.getEstado() != EstadoJornada.abierta) {
            throw conflicto("ESTADO_INVALIDO", "Solo se puede cerrar una jornada abierta");
        }
        j.setEstado(EstadoJornada.cerrada);
        bitacora.registrar(idAdmin, "CERRAR_JORNADA", "jornadas", j.getId(), Map.of("estado", "abierta"), Map.of("estado", "cerrada"));
        return detalleDe(j);
    }

    /** Cierra definitivamente: exige que ningún partido siga pendiente de marcador. */
    @Transactional
    public JornadaDetalleDto finalizar(Integer idAdmin, Integer idJornada) {
        Jornada j = buscarJornada(idJornada);
        if (j.getEstado() != EstadoJornada.abierta && j.getEstado() != EstadoJornada.cerrada) {
            throw conflicto("ESTADO_INVALIDO", "Solo se puede finalizar una jornada abierta o cerrada");
        }
        List<Partido> ps = partidosDe(j);
        long pendientes = ps.stream().filter(p -> p.getEstado() == EstadoPartido.programado).count();
        if (pendientes > 0) {
            throw conflicto("PARTIDOS_PENDIENTES",
                    "Faltan " + pendientes + (pendientes == 1 ? " partido" : " partidos") + " por capturar marcador o suspender");
        }
        if (ps.stream().noneMatch(p -> p.getEstado() == EstadoPartido.finalizado)) {
            throw conflicto("SIN_RESULTADOS", "Ningún partido tiene marcador; no se puede finalizar");
        }
        String antes = j.getEstado().name();
        j.setEstado(EstadoJornada.finalizada);
        bitacora.registrar(idAdmin, "FINALIZAR_JORNADA", "jornadas", j.getId(), Map.of("estado", antes), Map.of("estado", "finalizada"));
        return detalleDe(j);
    }

    /** "Ocultar" la quita de la vista de los usuarios. Una jornada abierta no se puede ocultar. */
    @Transactional
    public JornadaDetalleDto cambiarVisible(Integer idAdmin, Integer idJornada, boolean visible) {
        Jornada j = buscarJornada(idJornada);
        if (!visible && j.getEstado() == EstadoJornada.abierta) {
            throw conflicto("ESTADO_INVALIDO", "No se puede ocultar una jornada abierta; ciérrala primero");
        }
        if (j.isVisible() != visible) {
            j.setVisible(visible);
            bitacora.registrar(idAdmin, visible ? "MOSTRAR_JORNADA" : "OCULTAR_JORNADA", "jornadas", j.getId(),
                    Map.of("visible", !visible), Map.of("visible", visible));
        }
        return detalleDe(j);
    }

    // ================================================================ partidos

    @Transactional
    public JornadaDetalleDto agregarPartido(Integer idJornada, Integer idLocal, Integer idVisitante, Instant fechaHora) {
        Jornada j = buscarJornada(idJornada);
        exigirBorrador(j, "Los partidos solo se agregan mientras la jornada está en borrador");
        validarPartido(j, null, idLocal, idVisitante, fechaHora);
        short orden = (short) (partidos.maxOrden(j.getId()) + 1);
        guardar(new Partido(j.getId(), orden, idLocal, idVisitante, fechaHora));
        return detalleDe(j);
    }

    @Transactional
    public JornadaDetalleDto editarPartido(Integer idPartido, Integer idLocal, Integer idVisitante, Instant fechaHora) {
        Partido p = buscarPartido(idPartido);
        Jornada j = buscarJornada(p.getIdJornada());
        exigirBorrador(j, "Los partidos solo se editan mientras la jornada está en borrador");
        validarPartido(j, p.getId(), idLocal, idVisitante, fechaHora);
        p.programar(idLocal, idVisitante, fechaHora);
        guardar(p);
        return detalleDe(j);
    }

    @Transactional
    public JornadaDetalleDto eliminarPartido(Integer idPartido) {
        Partido p = buscarPartido(idPartido);
        Jornada j = buscarJornada(p.getIdJornada());
        exigirBorrador(j, "Los partidos solo se quitan mientras la jornada está en borrador");
        partidos.delete(p);
        partidos.flush();
        return detalleDe(j);
    }

    /**
     * Captura (o corrige) el marcador final. Solo cuando la jornada ya no acepta pronósticos,
     * para que nadie pueda apostar conociendo un resultado. El ranking se actualiza solo.
     */
    @Transactional
    public JornadaDetalleDto capturarResultado(Integer idAdmin, Integer idPartido, short golesLocal, short golesVisitante) {
        Partido p = buscarPartido(idPartido);
        Jornada j = buscarJornada(p.getIdJornada());
        exigirAceptaResultados(j);
        boolean correccion = p.getEstado() == EstadoPartido.finalizado;
        Map<String, Object> antes = new java.util.LinkedHashMap<>();
        antes.put("estado", p.getEstado().name());
        antes.put("goles_local", p.getGolesLocal());
        antes.put("goles_visitante", p.getGolesVisitante());
        p.finalizar(golesLocal, golesVisitante, idAdmin, clock.instant());
        partidos.flush();
        bitacora.registrar(idAdmin, correccion ? "CORREGIR_RESULTADO" : "CAPTURAR_RESULTADO", "partidos", p.getId(),
                antes, Map.of("estado", "finalizado", "goles_local", golesLocal, "goles_visitante", golesVisitante));
        return detalleDe(j);
    }

    /** Partido suspendido: no cuenta para puntos ni para el cierre de la jornada. */
    @Transactional
    public JornadaDetalleDto suspenderPartido(Integer idAdmin, Integer idPartido) {
        Partido p = buscarPartido(idPartido);
        Jornada j = buscarJornada(p.getIdJornada());
        if (j.getEstado() == EstadoJornada.borrador) {
            throw conflicto("ESTADO_INVALIDO", "En borrador simplemente quita el partido");
        }
        if (j.getEstado() == EstadoJornada.finalizada) {
            throw conflicto("JORNADA_FINALIZADA", "La jornada ya está finalizada");
        }
        String antes = p.getEstado().name();
        p.suspender();
        partidos.flush();
        bitacora.registrar(idAdmin, "SUSPENDER_PARTIDO", "partidos", p.getId(), Map.of("estado", antes), Map.of("estado", "suspendido"));
        return detalleDe(j);
    }

    // ================================================================ auxiliares

    private void validarPartido(Jornada j, Integer idPartido, Integer idLocal, Integer idVisitante, Instant fechaHora) {
        if (Objects.equals(idLocal, idVisitante)) {
            throw ApiException.invalido("MISMO_EQUIPO", "El local y el visitante deben ser equipos distintos");
        }
        Equipo local = equipos.findById(idLocal).filter(Equipo::isActivo)
                .orElseThrow(() -> ApiException.invalido("EQUIPO_INVALIDO", "El equipo local no existe"));
        Equipo visita = equipos.findById(idVisitante).filter(Equipo::isActivo)
                .orElseThrow(() -> ApiException.invalido("EQUIPO_INVALIDO", "El equipo visitante no existe"));
        if (!fechaHora.isAfter(clock.instant())) {
            throw ApiException.invalido("FECHA_PASADA", "La fecha y hora del partido debe ser futura");
        }
        for (Partido otro : partidosDe(j)) {
            if (otro.getId().equals(idPartido)) continue;
            for (Equipo e : List.of(local, visita)) {
                if (e.getId().equals(otro.getIdLocal()) || e.getId().equals(otro.getIdVisitante())) {
                    throw conflicto("EQUIPO_REPETIDO", e.getNombre() + " ya tiene partido en esta jornada");
                }
            }
        }
    }

    private void guardar(Partido p) {
        try {
            partidos.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            // Respaldo del trigger de la BD (dos admins guardando a la vez)
            throw conflicto("EQUIPO_REPETIDO", "Un equipo ya tiene partido en esta jornada");
        }
    }

    private void exigirBorrador(Jornada j, String mensaje) {
        if (j.getEstado() != EstadoJornada.borrador) {
            throw conflicto("ESTADO_INVALIDO", mensaje);
        }
    }

    private void exigirAceptaResultados(Jornada j) {
        if (j.getEstado() == EstadoJornada.borrador) {
            throw conflicto("ESTADO_INVALIDO", "La jornada todavía está en borrador");
        }
        if (j.getEstado() == EstadoJornada.finalizada) {
            throw conflicto("JORNADA_FINALIZADA", "La jornada ya está finalizada; sus marcadores no se pueden cambiar");
        }
        if (aceptaPronosticos(j, fechaCierre(partidosDe(j)), clock.instant())) {
            throw conflicto("ACEPTA_PRONOSTICOS",
                    "La jornada todavía acepta pronósticos. Ciérrala o espera a que empiece el primer partido");
        }
    }

    private Temporada temporadaActiva() {
        return temporadas.findFirstByActivaTrueOrderByIdDesc()
                .orElseThrow(() -> conflicto("SIN_TEMPORADA", "No hay una temporada activa"));
    }

    private Jornada buscarJornada(Integer id) {
        return jornadas.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "La jornada no existe"));
    }

    private Partido buscarPartido(Integer id) {
        return partidos.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "El partido no existe"));
    }

    private List<Partido> partidosDe(Jornada j) {
        return partidos.findByIdJornadaOrderByFechaHoraAscOrdenAsc(j.getId());
    }

    private JornadaDetalleDto detalleDe(Jornada j) {
        return vistas.detalle(j);
    }

    private JornadaDto aDto(Jornada j, List<Partido> ps, Instant ahora) {
        return vistas.resumen(j, ps, ahora);
    }

    /**
     * L/E/V igual que la columna generada "resultado" de la BD (fn_seleccion). Se calcula aquí porque
     * Hibernate no relee columnas generadas tras un UPDATE dentro de la misma transacción.
     */
    static String resultado(Partido p) {
        if (p.getEstado() != EstadoPartido.finalizado || p.getGolesLocal() == null || p.getGolesVisitante() == null) {
            return null;
        }
        int c = Short.compare(p.getGolesLocal(), p.getGolesVisitante());
        return c > 0 ? "L" : c < 0 ? "V" : "E";
    }

    /** Igual que la vista v_jornada_cierre: el inicio del primer partido no suspendido. */
    static Instant fechaCierre(List<Partido> ps) {
        return ps.stream().filter(p -> p.getEstado() != EstadoPartido.suspendido)
                .map(Partido::getFechaHora).min(Comparator.naturalOrder()).orElse(null);
    }

    static boolean aceptaPronosticos(Jornada j, Instant cierre, Instant ahora) {
        return j.getEstado() == EstadoJornada.abierta && (cierre == null || ahora.isBefore(cierre));
    }

    static EstadoVisual estadoVisual(Jornada j, Instant cierre, Instant ahora) {
        boolean empezo = cierre != null && !ahora.isBefore(cierre);
        return switch (j.getEstado()) {
            case borrador -> EstadoVisual.BORRADOR;
            case finalizada -> EstadoVisual.FINALIZADA;
            case abierta -> empezo ? EstadoVisual.EN_JUEGO : EstadoVisual.ACTIVA;
            case cerrada -> empezo ? EstadoVisual.EN_JUEGO : EstadoVisual.CERRADA;
        };
    }

    private static ApiException conflicto(String codigo, String mensaje) {
        return new ApiException(HttpStatus.CONFLICT, codigo, mensaje);
    }
}
