package mx.quiniela.api.quinielas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
class QuinielasTest {

    private static final String PASS = "Futbol2026";

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

    @BeforeEach
    void preparar() throws Exception {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios, jornadas, partidos, quinielas, pronosticos RESTART IDENTITY CASCADE");
        crearUsuario("admin", Rol.admin, EstadoUsuario.aprobado);
        crearUsuario("ana", Rol.usuario, EstadoUsuario.aprobado);
        crearUsuario("beto", Rol.usuario, EstadoUsuario.aprobado);
        crearUsuario("pepe", Rol.usuario, EstadoUsuario.pendiente);
        admin = token("admin");
        ana = token("ana");

        List<Integer> eq = jdbc.queryForList("SELECT id_equipo FROM equipos ORDER BY id_equipo", Integer.class);
        jornada = JsonPath.read(mvc.perform(como(admin, post("/api/admin/jornadas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"precio\":50}")).andReturn().getResponse().getContentAsString(), "$.jornada.id");
        for (int i = 0; i < 3; i++) {
            mvc.perform(como(admin, post("/api/admin/jornadas/" + jornada + "/partidos")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"idLocal\":%d,\"idVisitante\":%d,\"fechaHora\":\"%s\"}"
                            .formatted(eq.get(i * 2), eq.get(i * 2 + 1), Instant.now().plus(2 + i, ChronoUnit.HOURS))))
                    .andExpect(status().isCreated());
        }
        partidos = jdbc.queryForList("SELECT id_partido FROM partidos ORDER BY fecha_hora", Integer.class);
    }

    @Test
    void sinJornadaPublicadaNoHayActual() throws Exception {
        mvc.perform(como(ana, get("/api/jornadas/actual"))).andExpect(status().isNoContent());
        mvc.perform(como(ana, get("/api/quinielas"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.actual").doesNotExist())
                .andExpect(jsonPath("$.puedeCrear").value(false));
    }

    @Test
    void borradorIncompletoYLuegoEnviada() throws Exception {
        abrir();
        mvc.perform(como(ana, get("/api/jornadas/actual"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.jornada.aceptaPronosticos").value(true))
                .andExpect(jsonPath("$.partidos.length()").value(3));

        // Borrador con un solo partido
        ResultActions r = crear(ana, false, p(0, 2, 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.quiniela.estado").value("borrador"))
                .andExpect(jsonPath("$.quiniela.numero").value(1))
                .andExpect(jsonPath("$.quiniela.folio").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1000)))
                .andExpect(jsonPath("$.quiniela.monto").value(50.0))
                .andExpect(jsonPath("$.quiniela.pronosticados").value(1))
                .andExpect(jsonPath("$.quiniela.totalPartidos").value(3))
                .andExpect(jsonPath("$.quiniela.editable").value(true))
                .andExpect(jsonPath("$.quiniela.puntos").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pronosticos[0].puntos").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pronosticos[0].seleccion").value("L"));
        int id = JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.quiniela.id");

        // Enviar incompleta → error
        guardar(ana, id, true, p(0, 2, 1), p(1, 0, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FALTAN_PRONOSTICOS"));

        // Completa → pendiente de pago, con lógica espejo L/E/V
        guardar(ana, id, true, p(0, 2, 1), p(1, 0, 0), p(2, 1, 3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.quiniela.estado").value("pendiente_pago"))
                .andExpect(jsonPath("$.quiniela.enviadaAt").isNotEmpty())
                .andExpect(jsonPath("$.pronosticos.length()").value(3));
        assertThat(jdbc.queryForList("SELECT seleccion::text FROM pronosticos ORDER BY id_partido", String.class))
                .containsExactly("L", "E", "V");

        // Ya enviada no puede quedar incompleta, pero sí cambiar marcadores
        guardar(ana, id, false, p(0, 1, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FALTAN_PRONOSTICOS"));
        guardar(ana, id, false, p(0, 1, 1), p(1, 0, 0), p(2, 1, 3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.quiniela.estado").value("pendiente_pago"))
                .andExpect(jsonPath("$.pronosticos[?(@.idPartido == %d)].seleccion".formatted(partidos.get(0))).value("E"));

        // Segunda quiniela: número 2
        crear(ana, true, p(0, 0, 0), p(1, 0, 0), p(2, 0, 0)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.quiniela.numero").value(2))
                .andExpect(jsonPath("$.quiniela.estado").value("pendiente_pago"));

        mvc.perform(como(ana, get("/api/quinielas"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.puedeCrear").value(true))
                .andExpect(jsonPath("$.actual.id").value(jornada))
                .andExpect(jsonPath("$.quinielas.length()").value(2))
                .andExpect(jsonPath("$.jornadas.length()").value(1));
    }

    @Test
    void validaPartidosYGoles() throws Exception {
        abrir();
        crear(ana, false, "{\"idPartido\":999,\"golesLocal\":1,\"golesVisitante\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("PARTIDO_INVALIDO"));
        crear(ana, false, p(0, 1, 0), p(0, 2, 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("PARTIDO_REPETIDO"));
        crear(ana, false, p(0, -1, 0)).andExpect(status().isBadRequest());
        crear(ana, false, p(0, 21, 0)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quinielas", Integer.class)).isZero();
    }

    @Test
    void soloUsuariosAprobadosYJornadaAbierta() throws Exception {
        // Jornada en borrador: no acepta
        crear(ana, false, p(0, 1, 0)).andExpect(status().isNotFound()); // aún no es visible
        abrir();
        String pepe = token("pepe");
        crear(pepe, false, p(0, 1, 0)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_APROBADO"));
        mvc.perform(como(pepe, get("/api/quinielas"))).andExpect(jsonPath("$.puedeCrear").value(false))
                .andExpect(jsonPath("$.estadoUsuario").value("pendiente"))
                .andExpect(jsonPath("$.motivoNoPuedeCrear").value(org.hamcrest.Matchers.containsString("pendiente")));
    }

    @Test
    void alEmpezarElPrimerPartidoQuedaEnSoloLectura() throws Exception {
        abrir();
        int id = idQuiniela(crear(ana, true, p(0, 2, 1), p(1, 0, 0), p(2, 1, 3)));
        jdbc.update("UPDATE partidos SET fecha_hora = now() - interval '1 minute' WHERE id_partido = ?", partidos.get(0));

        guardar(ana, id, false, p(0, 3, 0), p(1, 0, 0), p(2, 1, 3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("JORNADA_CERRADA"));
        crear(ana, true, p(0, 2, 1), p(1, 0, 0), p(2, 1, 3)).andExpect(status().isConflict());
        mvc.perform(como(ana, delete("/api/quinielas/" + id))).andExpect(status().isConflict());
        mvc.perform(como(ana, get("/api/quinielas/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.quiniela.editable").value(false));
        mvc.perform(como(ana, get("/api/quinielas"))).andExpect(jsonPath("$.puedeCrear").value(false));
    }

    @Test
    void puntosConLosMarcadoresReales() throws Exception {
        abrir();
        int id = idQuiniela(crear(ana, true, p(0, 2, 1), p(1, 0, 0), p(2, 1, 3)));
        mvc.perform(como(admin, post("/api/admin/jornadas/" + jornada + "/cerrar"))).andExpect(status().isOk());
        resultado(partidos.get(0), 2, 1); // exacto → 3
        resultado(partidos.get(1), 1, 1); // empate → 1

        mvc.perform(como(ana, get("/api/quinielas/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.quiniela.puntos").value(4))
                .andExpect(jsonPath("$.quiniela.exactos").value(1))
                .andExpect(jsonPath("$.pronosticos[?(@.idPartido == %d)].puntos".formatted(partidos.get(0))).value(3))
                .andExpect(jsonPath("$.pronosticos[?(@.idPartido == %d)].exacto".formatted(partidos.get(0))).value(true))
                .andExpect(jsonPath("$.pronosticos[?(@.idPartido == %d)].puntos".formatted(partidos.get(1))).value(1));
        // El tercero sigue sin marcador
        String json = mvc.perform(como(ana, get("/api/quinielas/" + id))).andReturn().getResponse().getContentAsString();
        List<Object> sinPuntos = JsonPath.read(json, "$.pronosticos[?(@.idPartido == %d)].puntos".formatted(partidos.get(2)));
        assertThat(sinPuntos).containsExactly((Object) null);
    }

    @Test
    void quinielasAjenasYEliminar() throws Exception {
        abrir();
        int id = idQuiniela(crear(ana, false, p(0, 1, 0)));
        String beto = token("beto");
        mvc.perform(como(beto, get("/api/quinielas/" + id))).andExpect(status().isNotFound());
        guardar(beto, id, false, p(0, 1, 1)).andExpect(status().isNotFound());
        mvc.perform(como(beto, delete("/api/quinielas/" + id))).andExpect(status().isNotFound());
        mvc.perform(como(admin, get("/api/quinielas/" + id))).andExpect(status().isOk());

        jdbc.update("UPDATE quinielas SET estado = 'en_revision' WHERE id_quiniela = ?", id);
        mvc.perform(como(ana, delete("/api/quinielas/" + id))).andExpect(status().isConflict());
        jdbc.update("UPDATE quinielas SET estado = 'borrador' WHERE id_quiniela = ?", id);
        mvc.perform(como(ana, delete("/api/quinielas/" + id))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pronosticos", Integer.class)).isZero();
    }

    @Test
    void partidoSuspendidoNoSeExige() throws Exception {
        abrir();
        mvc.perform(como(admin, post("/api/admin/partidos/" + partidos.get(2) + "/suspender"))).andExpect(status().isOk());
        crear(ana, true, p(0, 1, 0), p(1, 0, 2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.quiniela.totalPartidos").value(2))
                .andExpect(jsonPath("$.quiniela.estado").value("pendiente_pago"));
        crear(ana, false, p(2, 1, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PARTIDO_INVALIDO"));
    }

    // ---------------------------------------------------------------- helpers

    private void abrir() throws Exception {
        mvc.perform(como(admin, post("/api/admin/jornadas/" + jornada + "/abrir"))).andExpect(status().isOk());
    }

    private void resultado(int partido, int gl, int gv) throws Exception {
        mvc.perform(como(admin, put("/api/admin/partidos/" + partido + "/resultado")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"golesLocal\":%d,\"golesVisitante\":%d}".formatted(gl, gv))).andExpect(status().isOk());
    }

    private String p(int indice, int gl, int gv) {
        return "{\"idPartido\":%d,\"golesLocal\":%d,\"golesVisitante\":%d}".formatted(partidos.get(indice), gl, gv);
    }

    private ResultActions crear(String token, boolean enviar, String... pronosticos) throws Exception {
        return mvc.perform(como(token, post("/api/quinielas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idJornada\":%d,\"enviar\":%s,\"pronosticos\":[%s]}"
                        .formatted(jornada, enviar, List.of(pronosticos).stream().collect(Collectors.joining(",")))));
    }

    private ResultActions guardar(String token, int id, boolean enviar, String... pronosticos) throws Exception {
        return mvc.perform(como(token, put("/api/quinielas/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enviar\":%s,\"pronosticos\":[%s]}".formatted(enviar, String.join(",", pronosticos))));
    }

    private static int idQuiniela(ResultActions r) throws Exception {
        return JsonPath.read(r.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.quiniela.id");
    }

    private void crearUsuario(String username, Rol rol, EstadoUsuario estado) {
        Usuario u = new Usuario(username, username, username + "@quiniela.mx", encoder.encode(PASS));
        u.setRol(rol);
        u.setEstado(estado);
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
