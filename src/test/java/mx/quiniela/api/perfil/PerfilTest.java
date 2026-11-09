package mx.quiniela.api.perfil;

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
class PerfilTest {

    private static final String PASS = "Futbol2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    private String admin;
    private String ana;
    private int idAna;

    @BeforeEach
    void preparar() throws Exception {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios, jornadas, partidos, quinielas, pronosticos, comprobantes, bitacora_admin RESTART IDENTITY CASCADE");
        crear("admin", "Administrador", Rol.admin, EstadoUsuario.aprobado);
        idAna = crear("ana", "Ana López", Rol.usuario, EstadoUsuario.aprobado);
        crear("beto", "Beto Ruiz", Rol.usuario, EstadoUsuario.aprobado);
        crear("pepe", "Pepe Pecas", Rol.usuario, EstadoUsuario.pendiente);
        admin = token("admin");
        ana = token("ana");
    }

    @Test
    void perfilConEstadisticasEHistorial() throws Exception {
        mvc.perform(como(ana, get("/api/perfil"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.username").value("ana"))
                .andExpect(jsonPath("$.estadisticas.quinielasJugadas").value(0))
                .andExpect(jsonPath("$.historial.length()").value(0));

        jornadaGanadaPorAna();

        mvc.perform(como(ana, get("/api/perfil"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estadisticas.quinielasJugadas").value(1))
                .andExpect(jsonPath("$.estadisticas.puntosTotales").value(1))
                .andExpect(jsonPath("$.estadisticas.exactosTotales").value(1))
                .andExpect(jsonPath("$.estadisticas.quinielasGanadas").value(1))
                .andExpect(jsonPath("$.estadisticas.premiosGanados").value(80.0))
                .andExpect(jsonPath("$.historial[0].posicion").value(1))
                .andExpect(jsonPath("$.historial[0].participantes").value(2))
                .andExpect(jsonPath("$.historial[0].ganador").value(true))
                .andExpect(jsonPath("$.historial[0].estadoJornada").value("finalizada"));
    }

    @Test
    void elUsuarioSoloCambiaSuNombre() throws Exception {
        mvc.perform(como(ana, put("/api/perfil")).contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(como(ana, put("/api/perfil")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Ana María López\",\"correo\":\"otro@x.mx\",\"username\":\"hacker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nombre").value("Ana María López"))
                .andExpect(jsonPath("$.usuario.correo").value("ana@quiniela.mx"))
                .andExpect(jsonPath("$.usuario.username").value("ana"));
    }

    @Test
    void adminBuscaCuentaYEdita() throws Exception {
        mvc.perform(como(admin, get("/api/admin/usuarios?q=RUIZ"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.elementos[0].username").value("beto"));
        mvc.perform(como(admin, get("/api/admin/usuarios?estado=pendiente&q=pe"))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(como(admin, get("/api/admin/usuarios/conteos"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.aprobado").value(3))
                .andExpect(jsonPath("$.pendiente").value(1))
                .andExpect(jsonPath("$.bloqueado").value(0));

        mvc.perform(como(admin, get("/api/admin/usuarios/" + idAna))).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nombre").value("Ana López"));
        editar(idAna, "Ana L.", "BETO@quiniela.mx").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CORREO_EN_USO"));
        editar(idAna, "Ana L.", "ana.nueva@quiniela.mx").andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.correo").value("ana.nueva@quiniela.mx"));
        editar(idAna, "Ana L.", "no-es-correo").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT accion FROM bitacora_admin", String.class)).containsExactly("EDITAR_USUARIO");

        mvc.perform(como(ana, get("/api/admin/usuarios/" + idAna))).andExpect(status().isForbidden());
    }

    @Test
    void eliminarUsuario() throws Exception {
        mvc.perform(como(admin, delete("/api/admin/usuarios/1"))).andExpect(status().isBadRequest()); // a sí mismo
        mvc.perform(como(admin, delete("/api/admin/usuarios/" + idAna))).andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"ana\",\"password\":\"%s\"}".formatted(PASS))).andExpect(status().isUnauthorized());
        mvc.perform(como(admin, get("/api/admin/usuarios?q=ana"))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(como(admin, get("/api/admin/usuarios/" + idAna))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesiones WHERE id_usuario = ? AND revocada_at IS NULL",
                Integer.class, idAna)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    /** Jornada con 1 partido: ana exacto (1), beto falla; ambos pagados; se finaliza. */
    private void jornadaGanadaPorAna() throws Exception {
        int eq1 = jdbc.queryForObject("SELECT MIN(id_equipo) FROM equipos", Integer.class);
        int j = JsonPath.read(mvc.perform(como(admin, post("/api/admin/jornadas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"precio\":50}")).andReturn().getResponse().getContentAsString(), "$.jornada.id");
        mvc.perform(como(admin, post("/api/admin/jornadas/" + j + "/partidos")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idLocal\":%d,\"idVisitante\":%d,\"fechaHora\":\"%s\"}"
                        .formatted(eq1, eq1 + 1, Instant.now().plus(2, ChronoUnit.HOURS)))).andExpect(status().isCreated());
        mvc.perform(como(admin, post("/api/admin/jornadas/" + j + "/abrir"))).andExpect(status().isOk());
        int partido = jdbc.queryForObject("SELECT id_partido FROM partidos", Integer.class);
        for (String[] u : List.of(new String[] {ana, "2", "0"}, new String[] {token("beto"), "0", "1"})) {
            mvc.perform(como(u[0], post("/api/quinielas")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"idJornada\":%d,\"enviar\":true,\"pronosticos\":[{\"idPartido\":%d,\"golesLocal\":%s,\"golesVisitante\":%s}]}"
                            .formatted(j, partido, u[1], u[2]))).andExpect(status().isCreated());
        }
        jdbc.update("UPDATE quinielas SET estado = 'participando'");
        mvc.perform(como(admin, post("/api/admin/jornadas/" + j + "/cerrar"))).andExpect(status().isOk());
        mvc.perform(como(admin, put("/api/admin/partidos/" + partido + "/resultado")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"golesLocal\":2,\"golesVisitante\":0}")).andExpect(status().isOk());
        mvc.perform(como(admin, post("/api/admin/jornadas/" + j + "/finalizar"))).andExpect(status().isOk());
    }

    private ResultActions editar(int id, String nombre, String correoNuevo) throws Exception {
        return mvc.perform(como(admin, put("/api/admin/usuarios/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"%s\",\"correo\":\"%s\"}".formatted(nombre, correoNuevo)));
    }

    private int crear(String username, String nombre, Rol rol, EstadoUsuario estado) {
        Usuario u = new Usuario(username, nombre, username + "@quiniela.mx", encoder.encode(PASS));
        u.setRol(rol);
        u.setEstado(estado);
        return usuarios.save(u).getId();
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
