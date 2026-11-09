package mx.quiniela.api.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import mx.quiniela.api.auth.LimiteIpLogin;
import mx.quiniela.api.correo.CorreoService;
import mx.quiniela.api.usuarios.EstadoUsuario;
import mx.quiniela.api.usuarios.Rol;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PagosTest {

    private static final String PASS = "Futbol2026";
    /** Cabecera mínima de un PNG: basta para que se detecte el tipo. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] PDF = "%PDF-1.4\n%prueba\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    private String admin;
    private String ana;
    private int jornada;
    private List<Integer> partidos;
    private int quiniela;

    @BeforeEach
    void preparar() throws Exception {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios, jornadas, partidos, quinielas, pronosticos, comprobantes, bitacora_admin RESTART IDENTITY CASCADE");
        crearUsuario("admin", Rol.admin);
        crearUsuario("ana", Rol.usuario);
        crearUsuario("beto", Rol.usuario);
        admin = token("admin");
        ana = token("ana");

        List<Integer> eq = jdbc.queryForList("SELECT id_equipo FROM equipos ORDER BY id_equipo", Integer.class);
        jornada = JsonPath.read(mvc.perform(como(admin, post("/api/admin/jornadas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"precio\":50}")).andReturn().getResponse().getContentAsString(), "$.jornada.id");
        for (int i = 0; i < 2; i++) {
            mvc.perform(como(admin, post("/api/admin/jornadas/" + jornada + "/partidos")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"idLocal\":%d,\"idVisitante\":%d,\"fechaHora\":\"%s\"}"
                            .formatted(eq.get(i * 2), eq.get(i * 2 + 1), Instant.now().plus(2 + i, ChronoUnit.HOURS))))
                    .andExpect(status().isCreated());
        }
        mvc.perform(como(admin, post("/api/admin/jornadas/" + jornada + "/abrir"))).andExpect(status().isOk());
        partidos = jdbc.queryForList("SELECT id_partido FROM partidos ORDER BY fecha_hora", Integer.class);
        quiniela = crearQuiniela(ana, true);
    }

    @Test
    void subirVerYValidar() throws Exception {
        mvc.perform(como(ana, get("/api/quinielas/" + quiniela)))
                .andExpect(jsonPath("$.pago.puedeSubir").value(true))
                .andExpect(jsonPath("$.pago.comprobante").doesNotExist());

        subir(ana, quiniela, "ticket.png", PNG).andExpect(status().isOk())
                .andExpect(jsonPath("$.quiniela.estado").value("en_revision"))
                .andExpect(jsonPath("$.pago.puedeSubir").value(false))
                .andExpect(jsonPath("$.pago.comprobante.estado").value("en_revision"))
                .andExpect(jsonPath("$.pago.comprobante.mimeType").value("image/png"));
        subir(ana, quiniela, "otro.png", PNG).andExpect(status().isConflict());

        int idC = JsonPath.read(mvc.perform(como(admin, get("/api/admin/pagos"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.conteos.en_revision").value(1))
                .andExpect(jsonPath("$.elementos.length()").value(1))
                .andExpect(jsonPath("$.elementos[0].visto").value(false))
                .andExpect(jsonPath("$.elementos[0].usuario.username").value("ana"))
                .andExpect(jsonPath("$.elementos[0].quiniela.monto").value(50.0))
                .andReturn().getResponse().getContentAsString(), "$.elementos[0].id");

        // No se valida a ciegas
        mvc.perform(como(admin, put("/api/admin/pagos/" + idC + "/aprobar"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SIN_VER"));

        mvc.perform(como(admin, get("/api/admin/pagos/" + idC + "/archivo"))).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));

        mvc.perform(como(admin, put("/api/admin/pagos/" + idC + "/aprobar"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("aprobado"))
                .andExpect(jsonPath("$.quiniela.estado").value("participando"));
        mvc.perform(como(admin, put("/api/admin/pagos/" + idC + "/aprobar"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("YA_REVISADO"));

        // Ya cuenta para el ranking y quedó en la bitácora
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v_ranking_jornada", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT accion FROM bitacora_admin WHERE entidad = 'comprobantes'", String.class))
                .containsExactly("VALIDAR_PAGO");
        verify(correo).enviarPagoValidado(eq("ana@quiniela.mx"), anyString(), anyString(), eq(1), anyInt());
    }

    @Test
    void rechazoYNuevoComprobante() throws Exception {
        subir(ana, quiniela, "ticket.pdf", PDF).andExpect(status().isOk());
        int idC = jdbc.queryForObject("SELECT id_comprobante FROM comprobantes", Integer.class);
        mvc.perform(como(admin, get("/api/admin/pagos/" + idC + "/archivo"))).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));

        mvc.perform(como(admin, put("/api/admin/pagos/" + idC + "/rechazar")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(como(admin, put("/api/admin/pagos/" + idC + "/rechazar")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"No se ve el monto\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("rechazado"))
                .andExpect(jsonPath("$.quiniela.estado").value("rechazada"));
        verify(correo).enviarPagoRechazado(eq("ana@quiniela.mx"), anyString(), anyString(), eq(1), anyInt(), eq("No se ve el monto"));

        mvc.perform(como(ana, get("/api/quinielas/" + quiniela)))
                .andExpect(jsonPath("$.quiniela.estado").value("rechazada"))
                .andExpect(jsonPath("$.pago.puedeSubir").value(true))
                .andExpect(jsonPath("$.pago.comprobante.motivoRechazo").value("No se ve el monto"));

        subir(ana, quiniela, "ticket2.png", PNG).andExpect(status().isOk())
                .andExpect(jsonPath("$.quiniela.estado").value("en_revision"));
        mvc.perform(como(admin, get("/api/admin/pagos")))
                .andExpect(jsonPath("$.conteos.en_revision").value(1))
                .andExpect(jsonPath("$.conteos.rechazado").value(1));
        mvc.perform(como(admin, get("/api/admin/pagos?estado=rechazado")))
                .andExpect(jsonPath("$.elementos[0].motivoRechazo").value("No se ve el monto"));
    }

    @Test
    void validaArchivoYEstado() throws Exception {
        subir(ana, quiniela, "falso.jpg", "esto no es una imagen".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("TIPO_INVALIDO"));
        subir(ana, quiniela, "vacio.png", new byte[0])
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("SIN_ARCHIVO"));

        int borrador = crearQuiniela(ana, false);
        subir(ana, borrador, "ticket.png", PNG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ESTADO_INVALIDO"));

        String beto = token("beto");
        subir(beto, quiniela, "ticket.png", PNG).andExpect(status().isNotFound());

        jdbc.update("UPDATE partidos SET fecha_hora = now() - interval '1 minute' WHERE id_partido = ?", partidos.get(0));
        subir(ana, quiniela, "ticket.png", PNG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("JORNADA_CERRADA"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comprobantes", Integer.class)).isZero();
    }

    @Test
    void permisosDelArchivo() throws Exception {
        subir(ana, quiniela, "ticket.png", PNG).andExpect(status().isOk());
        int idC = jdbc.queryForObject("SELECT id_comprobante FROM comprobantes", Integer.class);

        mvc.perform(como(ana, get("/api/admin/pagos"))).andExpect(status().isForbidden());
        mvc.perform(como(ana, get("/api/admin/pagos/" + idC + "/archivo"))).andExpect(status().isForbidden());
        mvc.perform(como(ana, get("/api/quinielas/" + quiniela + "/comprobante"))).andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
        mvc.perform(como(token("beto"), get("/api/quinielas/" + quiniela + "/comprobante"))).andExpect(status().isNotFound());

        // Que la dueña lo vea no cuenta como revisión del admin
        assertThat(jdbc.queryForObject("SELECT visto_at IS NULL FROM comprobantes", Boolean.class)).isTrue();
    }

    @Test
    void pagoManualSinComprobante() throws Exception {
        int borrador = crearQuiniela(ana, false);

        // Aparece en "por cobrar" aunque no haya subido nada (el borrador no)
        mvc.perform(como(admin, get("/api/admin/pagos"))).andExpect(jsonPath("$.conteos.por_cobrar").value(1));
        mvc.perform(como(admin, get("/api/admin/pagos/por-cobrar?idJornada=" + jornada))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quiniela.id").value(quiniela))
                .andExpect(jsonPath("$[0].usuario.username").value("ana"));

        mvc.perform(como(ana, post("/api/admin/quinielas/" + quiniela + "/pago-manual"))).andExpect(status().isForbidden());
        mvc.perform(como(admin, post("/api/admin/quinielas/" + borrador + "/pago-manual"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ESTADO_INVALIDO"));

        mvc.perform(como(admin, post("/api/admin/quinielas/" + quiniela + "/pago-manual"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"nota\":\"Efectivo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("manual"))
                .andExpect(jsonPath("$.estado").value("aprobado"))
                .andExpect(jsonPath("$.nota").value("Efectivo"))
                .andExpect(jsonPath("$.quiniela.estado").value("participando"));
        mvc.perform(como(admin, post("/api/admin/quinielas/" + quiniela + "/pago-manual"))).andExpect(status().isConflict());

        // La dueña lo ve como pagado, sin archivo que abrir ni subir
        mvc.perform(como(ana, get("/api/quinielas/" + quiniela)))
                .andExpect(jsonPath("$.quiniela.estado").value("participando"))
                .andExpect(jsonPath("$.pago.puedeSubir").value(false))
                .andExpect(jsonPath("$.pago.comprobante.tipo").value("manual"));
        mvc.perform(como(ana, get("/api/quinielas/" + quiniela + "/comprobante"))).andExpect(status().isNotFound());

        mvc.perform(como(admin, get("/api/admin/pagos?estado=aprobado")))
                .andExpect(jsonPath("$.conteos.por_cobrar").value(0))
                .andExpect(jsonPath("$.elementos[0].tipo").value("manual"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v_ranking_jornada", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT accion FROM bitacora_admin WHERE entidad = 'comprobantes'", String.class))
                .containsExactly("PAGO_MANUAL");
        verify(correo).enviarPagoValidado(eq("ana@quiniela.mx"), anyString(), anyString(), eq(1), anyInt());
    }

    @Test
    void pagoManualDespuesDeRechazoYConComprobanteEnRevision() throws Exception {
        subir(ana, quiniela, "ticket.png", PNG).andExpect(status().isOk());
        // Con un comprobante en revisión el admin debe revisarlo, no registrar otro pago
        mvc.perform(como(admin, post("/api/admin/quinielas/" + quiniela + "/pago-manual"))).andExpect(status().isConflict());

        int idC = jdbc.queryForObject("SELECT id_comprobante FROM comprobantes", Integer.class);
        mvc.perform(como(admin, get("/api/admin/pagos/" + idC + "/archivo"))).andExpect(status().isOk());
        mvc.perform(como(admin, put("/api/admin/pagos/" + idC + "/rechazar")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"Monto incompleto\"}")).andExpect(status().isOk());

        mvc.perform(como(admin, get("/api/admin/pagos/por-cobrar")))
                .andExpect(jsonPath("$[0].motivoRechazo").value("Monto incompleto"));
        // Después de jugar los partidos también se puede (pagó en efectivo antes del partido)
        jdbc.update("UPDATE partidos SET fecha_hora = now() - interval '1 minute'");
        mvc.perform(como(admin, post("/api/admin/quinielas/" + quiniela + "/pago-manual"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.quiniela.estado").value("participando"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions subir(String token, int idQuiniela, String nombre, byte[] datos) throws Exception {
        return mvc.perform(multipart("/api/quinielas/" + idQuiniela + "/comprobante")
                .file(new MockMultipartFile("archivo", nombre, "application/octet-stream", datos))
                .header("Authorization", "Bearer " + token));
    }

    private int crearQuiniela(String token, boolean enviar) throws Exception {
        String pron = "{\"idPartido\":%d,\"golesLocal\":1,\"golesVisitante\":0},{\"idPartido\":%d,\"golesLocal\":2,\"golesVisitante\":2}"
                .formatted(partidos.get(0), partidos.get(1));
        return JsonPath.read(mvc.perform(como(token, post("/api/quinielas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idJornada\":%d,\"enviar\":%s,\"pronosticos\":[%s]}".formatted(jornada, enviar, pron)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.quiniela.id");
    }

    private void crearUsuario(String username, Rol rol) {
        Usuario u = new Usuario(username, username, username + "@quiniela.mx", encoder.encode(PASS));
        u.setRol(rol);
        u.setEstado(EstadoUsuario.aprobado);
        usuarios.save(u);
    }

    private static MockHttpServletRequestBuilder como(String token, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + token);
    }

    private String token(String usuario) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"%s\",\"password\":\"%s\"}".formatted(usuario, PASS)))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }
}
