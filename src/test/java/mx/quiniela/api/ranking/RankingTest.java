package mx.quiniela.api.ranking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
class RankingTest {

    private static final String PASS = "Futbol2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    private String admin;
    private String ana;
    private String beto;
    private int jornada;
    private List<Integer> partidos;

    @BeforeEach
    void preparar() throws Exception {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios, jornadas, partidos, quinielas, pronosticos, comprobantes, bitacora_admin RESTART IDENTITY CASCADE");
        for (String u : List.of("admin", "ana", "beto", "caro")) {
            Usuario x = new Usuario(u, u, u + "@quiniela.mx", encoder.encode(PASS));
            x.setRol(u.equals("admin") ? Rol.admin : Rol.usuario);
            x.setEstado(EstadoUsuario.aprobado);
            usuarios.save(x);
        }
        admin = token("admin");
        ana = token("ana");
        beto = token("beto");

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

        quiniela(ana, 2, 1, 0, 0);    // pagada
        quiniela(beto, 1, 0, 1, 1);   // pagada
        quiniela(token("caro"), 2, 1, 0, 0); // sin pagar: no cuenta
        jdbc.update("UPDATE quinielas SET estado = 'participando' WHERE id_usuario IN (2, 3)");
    }

    @Test
    void antesDelCierreNoSeVenLosPronosticosAjenos() throws Exception {
        mvc.perform(como(ana, get("/api/ranking"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.jornada.id").value(jornada))
                .andExpect(jsonPath("$.pronosticosVisibles").value(false))
                .andExpect(jsonPath("$.filas.length()").value(2))
                .andExpect(jsonPath("$.bolsa.participantes").value(2))
                .andExpect(jsonPath("$.bolsa.bolsa").value(80.0))
                .andExpect(jsonPath("$.filas[?(@.username == 'ana')].pronosticos.length()").value(2))
                .andExpect(jsonPath("$.filas[?(@.username == 'ana')].esMia").value(true))
                .andExpect(jsonPath("$.filas[?(@.username == 'beto')].pronosticos.length()").value(0));
    }

    @Test
    void conMarcadoresOrdenaPorPuntosYAlFinalizarRepartePremio() throws Exception {
        mvc.perform(como(admin, post("/api/admin/jornadas/" + jornada + "/cerrar"))).andExpect(status().isOk());
        resultado(partidos.get(0), 2, 1); // ana exacto (3), beto tendencia (1)
        resultado(partidos.get(1), 1, 1); // ana tendencia (1), beto exacto (3)

        mvc.perform(como(beto, get("/api/ranking?idJornada=" + jornada))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pronosticosVisibles").value(true))
                .andExpect(jsonPath("$.filas[0].posicion").value(1))
                .andExpect(jsonPath("$.filas[1].posicion").value(1)) // empate: comparten lugar
                .andExpect(jsonPath("$.filas[0].puntos").value(4))
                .andExpect(jsonPath("$.filas[0].exactos").value(1))
                .andExpect(jsonPath("$.filas[?(@.username == 'ana')].pronosticos.length()").value(2))
                .andExpect(jsonPath("$.filas[0].ganador").value(false)); // aún no finaliza

        resultado(partidos.get(1), 0, 0); // corrección: ana exacto (3) → 6; beto tendencia (1) → 2
        mvc.perform(como(admin, post("/api/admin/jornadas/" + jornada + "/finalizar"))).andExpect(status().isOk());

        mvc.perform(como(ana, get("/api/ranking?idJornada=" + jornada)))
                .andExpect(jsonPath("$.filas[0].username").value("ana"))
                .andExpect(jsonPath("$.filas[0].puntos").value(6))
                .andExpect(jsonPath("$.filas[0].ganador").value(true))
                .andExpect(jsonPath("$.filas[0].premio").value(80.0))
                .andExpect(jsonPath("$.filas[1].posicion").value(2))
                .andExpect(jsonPath("$.filas[1].ganador").value(false));

        mvc.perform(como(ana, get("/api/ranking/jornadas"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.jornadas.length()").value(1))
                .andExpect(jsonPath("$.jornadas[0].estadoVisual").value("FINALIZADA"));
    }

    @Test
    void jornadaEnBorradorNoSeVe() throws Exception {
        int otra = JsonPath.read(mvc.perform(como(admin, post("/api/admin/jornadas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"precio\":50}")).andReturn().getResponse().getContentAsString(), "$.jornada.id");
        mvc.perform(como(ana, get("/api/ranking?idJornada=" + otra))).andExpect(status().isNotFound());
        mvc.perform(como(admin, get("/api/ranking?idJornada=" + otra))).andExpect(status().isOk())
                .andExpect(jsonPath("$.filas.length()").value(0));
    }

    // ---------------------------------------------------------------- helpers

    private void quiniela(String token, int l1, int v1, int l2, int v2) throws Exception {
        mvc.perform(como(token, post("/api/quinielas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idJornada\":%d,\"enviar\":true,\"pronosticos\":[{\"idPartido\":%d,\"golesLocal\":%d,\"golesVisitante\":%d},{\"idPartido\":%d,\"golesLocal\":%d,\"golesVisitante\":%d}]}"
                        .formatted(jornada, partidos.get(0), l1, v1, partidos.get(1), l2, v2)))
                .andExpect(status().isCreated());
    }

    private void resultado(int partido, int gl, int gv) throws Exception {
        mvc.perform(como(admin, put("/api/admin/partidos/" + partido + "/resultado")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"golesLocal\":%d,\"golesVisitante\":%d}".formatted(gl, gv))).andExpect(status().isOk());
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
