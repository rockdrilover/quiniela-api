package mx.quiniela.api.pagos;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import mx.quiniela.api.admin.Bitacora;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.correo.CorreoService;
import mx.quiniela.api.jornadas.EstadoJornada;
import mx.quiniela.api.jornadas.Jornada;
import mx.quiniela.api.jornadas.JornadaRepository;
import mx.quiniela.api.jornadas.JornadaVistas;
import mx.quiniela.api.pagos.PagoDtos.ComprobanteAdminDto;
import mx.quiniela.api.pagos.PagoDtos.ComprobanteDto;
import mx.quiniela.api.pagos.PagoDtos.JornadaRef;
import mx.quiniela.api.pagos.PagoDtos.ListaPagosDto;
import mx.quiniela.api.pagos.PagoDtos.PagoDto;
import mx.quiniela.api.pagos.PagoDtos.QuinielaRef;
import mx.quiniela.api.pagos.PagoDtos.UsuarioRef;
import mx.quiniela.api.quinielas.Quiniela;
import mx.quiniela.api.quinielas.QuinielaRepository;
import mx.quiniela.api.usuarios.EstadoUsuario;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/**
 * Pagos de quinielas (pantalla 7).
 *
 * El usuario sube su comprobante (foto o PDF) antes del cierre de la jornada: así nadie paga solo
 * cuando ya sabe que va ganando. La quiniela pasa a "en revisión". El admin abre el archivo y solo
 * entonces puede validar (→ participando, entra al ranking) o rechazar con un motivo (→ rechazada;
 * el usuario puede subir otro mientras la jornada siga abierta).
 */
@Service
public class PagosService {

    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private final ComprobanteRepository comprobantes;
    private final QuinielaRepository quinielas;
    private final JornadaRepository jornadas;
    private final UsuarioRepository usuarios;
    private final JornadaVistas vistas;
    private final AlmacenArchivos almacen;
    private final Bitacora bitacora;
    private final CorreoService correo;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PagosService(ComprobanteRepository comprobantes, QuinielaRepository quinielas, JornadaRepository jornadas,
                        UsuarioRepository usuarios, JornadaVistas vistas, AlmacenArchivos almacen, Bitacora bitacora,
                        CorreoService correo, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.comprobantes = comprobantes;
        this.quinielas = quinielas;
        this.jornadas = jornadas;
        this.usuarios = usuarios;
        this.vistas = vistas;
        this.almacen = almacen;
        this.bitacora = bitacora;
        this.correo = correo;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Archivo(byte[] datos, String mimeType, String nombre) {
    }

    // ================================================================ usuario

    /** Estado del pago para la pantalla de la quiniela. */
    @Transactional(readOnly = true)
    public PagoDto pagoDe(Quiniela q, boolean usuarioAprobado, boolean aceptaPronosticos) {
        Comprobante c = comprobantes.findFirstByIdQuinielaOrderByIdDesc(q.getId()).orElse(null);
        return new PagoDto(usuarioAprobado && aceptaPronosticos && q.puedeSubirPago(), ComprobanteDto.de(c));
    }

    @Transactional
    public void subir(Integer idUsuario, Integer idQuiniela, MultipartFile archivo) {
        Quiniela q = quinielas.findById(idQuiniela).filter(x -> x.getIdUsuario().equals(idUsuario))
                .orElseThrow(PagosService::quinielaNoEncontrada);
        Usuario u = usuarios.findById(idUsuario).orElseThrow();
        if (u.getEstado() != EstadoUsuario.aprobado) {
            throw ApiException.prohibido("USUARIO_NO_APROBADO", "Tu cuenta está pendiente de aprobación por el administrador");
        }
        if (!q.puedeSubirPago()) {
            throw conflicto("ESTADO_INVALIDO", switch (q.getEstado()) {
                case borrador -> "Primero envía la quiniela con todos tus pronósticos";
                case en_revision -> "Ya hay un comprobante en revisión para esta quiniela";
                case participando -> "El pago de esta quiniela ya fue validado";
                default -> "Esta quiniela no acepta comprobantes";
            });
        }
        if (!vistas.aceptaPronosticos(jornadas.findById(q.getIdJornada()).orElseThrow())) {
            throw conflicto("JORNADA_CERRADA", "La jornada ya cerró: los pagos se reciben solo antes del primer partido");
        }

        byte[] datos = leer(archivo);
        TipoArchivo tipo = TipoArchivo.detectar(datos)
                .orElseThrow(() -> ApiException.invalido("TIPO_INVALIDO", "Sube una foto (JPG, PNG o WEBP) o un PDF"));

        String clave = almacen.guardar(datos, tipo.extension);
        // Si la transacción falla después, el archivo no debe quedar huérfano
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) almacen.borrar(clave);
            }
        });
        comprobantes.save(new Comprobante(q.getId(), clave, tipo.mime, datos.length));
        q.pagoEnRevision(clock.instant());
        comprobantes.flush();
    }

    /** El dueño puede volver a ver su último comprobante (el admin usa archivoAdmin). */
    @Transactional(readOnly = true)
    public Archivo archivoPropio(Integer idUsuario, boolean esAdmin, Integer idQuiniela) {
        Quiniela q = quinielas.findById(idQuiniela).filter(x -> esAdmin || x.getIdUsuario().equals(idUsuario))
                .orElseThrow(PagosService::quinielaNoEncontrada);
        Comprobante c = comprobantes.findFirstByIdQuinielaOrderByIdDesc(q.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "Esta quiniela no tiene comprobante"));
        return archivoDe(c);
    }

    // ================================================================ admin

    @Transactional(readOnly = true)
    public ListaPagosDto listar(EstadoComprobante estado, Integer idJornada) {
        Map<String, Long> conteos = new LinkedHashMap<>();
        for (EstadoComprobante e : EstadoComprobante.values()) conteos.put(e.name(), 0L);
        MapSqlParameterSource p = new MapSqlParameterSource("j", idJornada);
        jdbc.query("""
                SELECT c.estado::text AS estado, COUNT(*) AS n FROM comprobantes c JOIN quinielas q USING (id_quiniela)
                WHERE (CAST(:j AS int) IS NULL OR q.id_jornada = :j) GROUP BY c.estado""", p,
                rs -> {
                    conteos.put(rs.getString("estado"), rs.getLong("n"));
                });
        p.addValue("estado", estado.name());
        String orden = estado == EstadoComprobante.en_revision ? "c.subido_at ASC" : "COALESCE(c.revisado_at, c.subido_at) DESC";
        List<ComprobanteAdminDto> filas = jdbc.query(SELECT_ADMIN + """
                WHERE c.estado = CAST(:estado AS estado_comprobante)
                  AND (CAST(:j AS int) IS NULL OR q.id_jornada = :j)
                """ + " ORDER BY " + orden + " LIMIT 300", p, PagosService::filaAdmin);
        return new ListaPagosDto(conteos, filas);
    }

    /** Abrir el archivo lo marca como "visto": requisito para poder validarlo. */
    @Transactional
    public Archivo archivoAdmin(Integer idAdmin, Integer idComprobante) {
        Comprobante c = buscar(idComprobante);
        c.marcarVisto(idAdmin, clock.instant());
        return archivoDe(c);
    }

    @Transactional
    public ComprobanteAdminDto aprobar(Integer idAdmin, Integer idComprobante) {
        Comprobante c = buscarParaRevisar(idComprobante);
        Quiniela q = quinielas.findById(c.getIdQuiniela()).orElseThrow();
        Instant ahora = clock.instant();
        c.aprobar(idAdmin, ahora);
        q.pagoAprobado(ahora);
        comprobantes.flush();
        bitacora.registrar(idAdmin, "VALIDAR_PAGO", "comprobantes", c.getId(),
                Map.of("estado", "en_revision"), Map.of("estado", "aprobado", "folio", q.getFolio()));
        avisar(q, true, null);
        return fila(c.getId());
    }

    @Transactional
    public ComprobanteAdminDto rechazar(Integer idAdmin, Integer idComprobante, String motivo) {
        Comprobante c = buscarParaRevisar(idComprobante);
        Quiniela q = quinielas.findById(c.getIdQuiniela()).orElseThrow();
        Instant ahora = clock.instant();
        String m = motivo.trim();
        c.rechazar(idAdmin, ahora, m);
        q.pagoRechazado(ahora);
        comprobantes.flush();
        bitacora.registrar(idAdmin, "RECHAZAR_PAGO", "comprobantes", c.getId(),
                Map.of("estado", "en_revision"), Map.of("estado", "rechazado", "motivo", m, "folio", q.getFolio()));
        avisar(q, false, m);
        return fila(c.getId());
    }

    // ================================================================ auxiliares

    private Comprobante buscarParaRevisar(Integer id) {
        Comprobante c = buscar(id);
        if (c.getEstado() != EstadoComprobante.en_revision) {
            throw conflicto("YA_REVISADO", "Este comprobante ya fue revisado");
        }
        if (c.getVistoAt() == null) {
            throw conflicto("SIN_VER", "Abre el comprobante antes de validarlo o rechazarlo");
        }
        Quiniela q = quinielas.findById(c.getIdQuiniela()).orElseThrow();
        Jornada j = jornadas.findById(q.getIdJornada()).orElseThrow();
        if (j.getEstado() == EstadoJornada.finalizada) {
            throw conflicto("JORNADA_FINALIZADA", "La jornada ya está finalizada; su ranking es definitivo");
        }
        return c;
    }

    private void avisar(Quiniela q, boolean aprobado, String motivo) {
        Usuario u = usuarios.findById(q.getIdUsuario()).orElseThrow();
        String jornada = jornadas.findById(q.getIdJornada()).map(Jornada::getNombre).orElse("");
        // Se manda al confirmar la transacción para no avisar algo que al final no se guardó
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (aprobado) correo.enviarPagoValidado(u.getCorreo(), u.getNombre(), jornada, q.getNumero(), q.getFolio());
                else correo.enviarPagoRechazado(u.getCorreo(), u.getNombre(), jornada, q.getNumero(), q.getFolio(), motivo);
            }
        });
    }

    private Archivo archivoDe(Comprobante c) {
        String ext = c.getArchivoKey().substring(c.getArchivoKey().lastIndexOf('.') + 1);
        return new Archivo(almacen.leer(c.getArchivoKey()), c.getMimeType(), "comprobante-" + c.getId() + "." + ext);
    }

    private static byte[] leer(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw ApiException.invalido("SIN_ARCHIVO", "Selecciona la foto o el PDF de tu comprobante");
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw ApiException.invalido("ARCHIVO_GRANDE", "El archivo pesa más de 10 MB");
        }
        try {
            return archivo.getBytes();
        } catch (IOException e) {
            throw ApiException.invalido("SIN_ARCHIVO", "No se pudo leer el archivo; intenta de nuevo");
        }
    }

    private Comprobante buscar(Integer id) {
        return comprobantes.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "El comprobante no existe"));
    }

    private ComprobanteAdminDto fila(Integer id) {
        return jdbc.queryForObject(SELECT_ADMIN + " WHERE c.id_comprobante = :id",
                new MapSqlParameterSource("id", id), PagosService::filaAdmin);
    }

    private static final String SELECT_ADMIN = """
            SELECT c.id_comprobante, c.estado::text AS estado, c.subido_at, c.mime_type, c.tamano_bytes,
                   c.visto_at, c.revisado_at, c.motivo_rechazo,
                   q.id_quiniela, q.folio, q.numero_en_jornada, q.estado::text AS estado_quiniela, q.monto,
                   u.id_usuario, u.username::text AS username, u.nombre,
                   j.id_jornada, j.nombre AS jornada
            FROM comprobantes c
            JOIN quinielas q ON q.id_quiniela = c.id_quiniela
            JOIN usuarios u  ON u.id_usuario = q.id_usuario
            JOIN jornadas j  ON j.id_jornada = q.id_jornada
            """;

    private static ComprobanteAdminDto filaAdmin(ResultSet rs, int i) throws SQLException {
        return new ComprobanteAdminDto(
                rs.getInt("id_comprobante"), rs.getString("estado"), instante(rs, "subido_at"),
                rs.getString("mime_type"), rs.getInt("tamano_bytes"), rs.getTimestamp("visto_at") != null,
                instante(rs, "revisado_at"), rs.getString("motivo_rechazo"),
                new QuinielaRef(rs.getInt("id_quiniela"), rs.getInt("folio"), rs.getInt("numero_en_jornada"),
                        rs.getString("estado_quiniela"), rs.getBigDecimal("monto")),
                new UsuarioRef(rs.getInt("id_usuario"), rs.getString("username"), rs.getString("nombre")),
                new JornadaRef(rs.getInt("id_jornada"), rs.getString("jornada")));
    }

    private static Instant instante(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static ApiException quinielaNoEncontrada() {
        return new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "La quiniela no existe");
    }

    private static ApiException conflicto(String codigo, String mensaje) {
        return new ApiException(HttpStatus.CONFLICT, codigo, mensaje);
    }
}
