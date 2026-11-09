package mx.quiniela.api.torneos;

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

import org.junit.jupiter.api.AfterEach;
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
class TorneosTest {

    private static final String PASS = "Futbol2026";
    private static final String BASE = "Apertura 2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    private String admin;
    private int apertura;

    @BeforeEach
    void preparar() throws Exception {
        limpiar();
        crearUsuario("admin", Rol.admin);
        crearUsuario("ana", Rol.usuario);
        admin = token("admin");
        apertura = jdbc.queryForObject("SELECT id_temporada FROM temporadas WHERE nombre = ?", Integer.class, BASE);
    }

    /** Las demás pruebas cuentan con que el torneo base esté en curso. */
    @AfterEach
    void limpiar() {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios, jornadas, partidos, quinielas, pronosticos, comprobantes, bitacora_admin RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM temporadas WHERE nombre <> ?", BASE);
        jdbc.update("UPDATE temporadas SET estado = 'en_curso' WHERE nombre = ?", BASE);
    }

    @Test
    void cicloDeVidaDeUnTorneo() throws Exception {
        int clausura = JsonPath.read(crearTorneo("Clausura 2027").andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("proximo"))
                .andExpect(jsonPath("$.totalJornadas").value(0))
                .andReturn().getResponse().getContentAsString(), "$.id");
        crearTorneo("clausura 2027").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("NOMBRE_EN_USO"));

        // Se preparan jornadas en un torneo próximo, pero no se abren hasta iniciarlo
        int j = idJornada(crearJornada(clausura).andExpect(status().isCreated())
                .andExpect(jsonPath("$.jornada.numero").value(1)));
        agregarPartido(j);
        mvc.perform(como(post("/api/admin/jornadas/" + j + "/abrir"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TORNEO_NO_EN_CURSO"));

        // Solo uno en curso
        mvc.perform(como(post("/api/admin/torneos/" + clausura + "/iniciar"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("OTRO_EN_CURSO"));
        mvc.perform(como(post("/api/admin/torneos/" + apertura + "/finalizar"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("finalizado"));
        mvc.perform(como(post("/api/admin/torneos/" + clausura + "/iniciar"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("en_curso"));
        mvc.perform(como(post("/api/admin/jornadas/" + j + "/abrir"))).andExpect(status().isOk());

        // No se finaliza con jornadas pendientes
        mvc.perform(como(post("/api/admin/torneos/" + clausura + "/finalizar"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("JORNADAS_PENDIENTES"));

        mvc.perform(como(get("/api/admin/torneos"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Clausura 2027"))
                .andExpect(jsonPath("$[0].totalJornadas").value(1));
        assertThat(jdbc.queryForList("SELECT accion FROM bitacora_admin WHERE entidad = 'temporadas' ORDER BY id_bitacora", String.class))
                .containsExactly("CREAR_TORNEO", "FINALIZAR_TORNEO", "INICIAR_TORNEO");
    }

    @Test
    void torneoFinalizadoNoAceptaJornadas() throws Exception {
        mvc.perform(como(post("/api/admin/torneos/" + apertura + "/finalizar"))).andExpect(status().isOk());

        crearJornada(apertura).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("TORNEO_FINALIZADO"));
        // Sin torneo en curso tampoco hay a dónde crearla
        mvc.perform(como(post("/api/admin/jornadas")).contentType(MediaType.APPLICATION_JSON).content("{\"precio\":50}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SIN_TEMPORADA"));

        mvc.perform(como(get("/api/admin/jornadas?idTemporada=" + apertura))).andExpect(status().isOk())
                .andExpect(jsonPath("$.temporada").value(BASE))
                .andExpect(jsonPath("$.estadoTemporada").value("finalizado"));
    }

    @Test
    void elNombreDeLaJornadaSeEditaEnCualquierEstado() throws Exception {
        int j = idJornada(crearJornada(apertura));
        agregarPartido(j);
        mvc.perform(como(post("/api/admin/jornadas/" + j + "/abrir"))).andExpect(status().isOk());

        renombrar(j, "{\"nombre\":\"  Jornada 1 · Clásicos  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.jornada.nombre").value("Jornada 1 · Clásicos"))
                .andExpect(jsonPath("$.jornada.estado").value("abierta"));
        renombrar(j, "{\"nombre\":\"  \"}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT accion FROM bitacora_admin WHERE accion = 'RENOMBRAR_JORNADA'", String.class)).hasSize(1);
    }

    @Test
    void soloElAdminYSoloTorneosSinJornadasSeEliminan() throws Exception {
        mvc.perform(get("/api/admin/torneos").header("Authorization", "Bearer " + token("ana"))).andExpect(status().isForbidden());

        int vacio = JsonPath.read(crearTorneo("Copa 2027").andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(como(delete("/api/admin/torneos/" + apertura))).andExpect(status().isConflict());
        mvc.perform(como(delete("/api/admin/torneos/" + vacio))).andExpect(status().isNoContent());

        int otro = JsonPath.read(crearTorneo("Copa 2028").andReturn().getResponse().getContentAsString(), "$.id");
        crearJornada(otro).andExpect(status().isCreated());
        mvc.perform(como(delete("/api/admin/torneos/" + otro))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TIENE_JORNADAS"));

        mvc.perform(como(post("/api/admin/torneos")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Fechas mal\",\"fechaInicio\":\"2027-05-01\",\"fechaFin\":\"2027-01-01\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("FECHAS_INVALIDAS"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions crearTorneo(String nombre) throws Exception {
        return mvc.perform(como(post("/api/admin/torneos")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"%s\",\"fechaInicio\":\"2027-01-08\"}".formatted(nombre)));
    }

    private ResultActions crearJornada(int idTemporada) throws Exception {
        return mvc.perform(como(post("/api/admin/jornadas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idTemporada\":%d,\"precio\":50}".formatted(idTemporada)));
    }

    private ResultActions renombrar(int idJornada, String cuerpo) throws Exception {
        return mvc.perform(como(put("/api/admin/jornadas/" + idJornada + "/nombre")).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private void agregarPartido(int idJornada) throws Exception {
        List<Integer> eq = jdbc.queryForList("SELECT id_equipo FROM equipos ORDER BY id_equipo", Integer.class);
        mvc.perform(como(post("/api/admin/jornadas/" + idJornada + "/partidos")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idLocal\":%d,\"idVisitante\":%d,\"fechaHora\":\"%s\"}"
                        .formatted(eq.get(0), eq.get(1), Instant.now().plus(3, ChronoUnit.HOURS))))
                .andExpect(status().isCreated());
    }

    private static int idJornada(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.jornada.id");
    }

    private void crearUsuario(String username, Rol rol) {
        Usuario u = new Usuario(username, username, username + "@quiniela.mx", encoder.encode(PASS));
        u.setRol(rol);
        u.setEstado(EstadoUsuario.aprobado);
        usuarios.save(u);
    }

    private MockHttpServletRequestBuilder como(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + admin);
    }

    private String token(String usuario) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"%s\",\"password\":\"%s\"}".formatted(usuario, PASS)))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }
}
