package mx.quiniela.api.jornadas;

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
class JornadasAdminTest {

    private static final String PASS = "Futbol2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    private String tokenAdmin;
    private List<Integer> eq; // ids de equipos

    @BeforeEach
    void preparar() throws Exception {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios, jornadas, partidos RESTART IDENTITY CASCADE");
        Usuario admin = new Usuario("admin", "Administrador", "admin@quiniela.mx", encoder.encode(PASS));
        admin.setRol(Rol.admin);
        admin.setEstado(EstadoUsuario.aprobado);
        usuarios.save(admin);
        tokenAdmin = token("admin");
        eq = jdbc.queryForList("SELECT id_equipo FROM equipos ORDER BY id_equipo", Integer.class);
    }

    @Test
    void catalogoDeEquipos() throws Exception {
        mvc.perform(auth(get("/api/equipos"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(18))
                .andExpect(jsonPath("$[0].abreviatura").isNotEmpty())
                .andExpect(jsonPath("$[0].urlLogo").value("/equipos/AME.png"));
    }

    @Test
    void corregirPartidosYPrecioConLaJornadaActiva() throws Exception {
        int j = idJornada(crear(50));
        int p1 = idUltimoPartido(agregar(j, eq.get(0), eq.get(1), enHoras(24)));
        abrir(j).andExpect(status().isOk());

        // Abierta y sin empezar: se corrigen equipos y fecha
        editarPartido(p1, eq.get(0), eq.get(2), enHoras(30)).andExpect(status().isOk())
                .andExpect(jsonPath("$.partidos[0].visitante.id").value(eq.get(2)));
        // El precio se corrige y las quinielas sin pago toman el nuevo
        jdbc.update("INSERT INTO quinielas (id_usuario, id_jornada, monto) VALUES (1, ?, 50)", j);
        editarJornada(j, 80, 70).andExpect(status().isOk())
                .andExpect(jsonPath("$.jornada.precio").value(80))
                .andExpect(jsonPath("$.jornada.pctPremio").value(70));
        assertThat(jdbc.queryForObject("SELECT monto FROM quinielas WHERE id_jornada = ?", Integer.class, j)).isEqualTo(80);

        // Con una quiniela pagada el precio ya no cambia (el % sí)
        jdbc.update("UPDATE quinielas SET estado = 'participando' WHERE id_jornada = ?", j);
        editarJornada(j, 90, 70).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("TIENE_PAGOS"));
        editarJornada(j, 80, 75).andExpect(status().isOk());

        // Cerrada: solo la fecha
        accion(j, "cerrar").andExpect(status().isOk());
        editarPartido(p1, eq.get(0), eq.get(3), enHoras(30)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("YA_EMPEZO"));
        editarPartido(p1, eq.get(0), eq.get(2), enHoras(40)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bitacora_admin WHERE accion = 'EDITAR_PARTIDO'", Integer.class)).isEqualTo(2);
    }

    @Test
    void crearJornadaNumeraSolaYNaceEnBorrador() throws Exception {
        crear(50).andExpect(status().isCreated())
                .andExpect(jsonPath("$.jornada.numero").value(1))
                .andExpect(jsonPath("$.jornada.nombre").value("Jornada 1"))
                .andExpect(jsonPath("$.jornada.estadoVisual").value("BORRADOR"))
                .andExpect(jsonPath("$.jornada.visible").value(false))
                .andExpect(jsonPath("$.jornada.pctPremio").value(80));
        crear(50).andExpect(jsonPath("$.jornada.numero").value(2));
        mvc.perform(auth(get("/api/admin/jornadas")))
                .andExpect(jsonPath("$.temporada").value("Apertura 2026"))
                .andExpect(jsonPath("$.jornadas[0].numero").value(2)); // la más nueva primero
        crear(0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.precio").exists());
    }

    @Test
    void reglasDePartidos() throws Exception {
        int j = idJornada(crear(50));
        agregar(j, eq.get(0), eq.get(1), enHoras(48)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.partidos[0].local.id").value(eq.get(0)))
                .andExpect(jsonPath("$.jornada.totalPartidos").value(1));
        agregar(j, eq.get(1), eq.get(2), enHoras(48)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("EQUIPO_REPETIDO"));
        agregar(j, eq.get(2), eq.get(2), enHoras(48)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("MISMO_EQUIPO"));
        agregar(j, eq.get(2), eq.get(3), enHoras(-1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FECHA_PASADA"));
        agregar(j, eq.get(2), eq.get(3), enHoras(24)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.jornada.fechaCierre").exists())
                .andExpect(jsonPath("$.partidos[0].local.id").value(eq.get(2))); // ordenados por fecha
    }

    @Test
    void cicloCompletoDeUnaJornada() throws Exception {
        int j = idJornada(crear(50));
        abrir(j).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SIN_PARTIDOS"));

        agregar(j, eq.get(0), eq.get(1), enHoras(24));
        int p2 = idUltimoPartido(agregar(j, eq.get(2), eq.get(3), enHoras(26)));
        int p1 = jdbc.queryForObject("SELECT MIN(id_partido) FROM partidos", Integer.class);

        abrir(j).andExpect(status().isOk())
                .andExpect(jsonPath("$.jornada.estadoVisual").value("ACTIVA"))
                .andExpect(jsonPath("$.jornada.visible").value(true))
                .andExpect(jsonPath("$.jornada.aceptaPronosticos").value(true));

        // Solo una abierta a la vez
        int otra = idJornada(crear(50));
        agregar(otra, eq.get(4), eq.get(5), enHoras(30));
        abrir(otra).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("OTRA_ABIERTA"));

        // Abierta: ya no se tocan los partidos ni se capturan resultados
        agregar(j, eq.get(4), eq.get(5), enHoras(30)).andExpect(status().isConflict());
        resultado(p1, 2, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ACEPTA_PRONOSTICOS"));
        mvc.perform(auth(put("/api/admin/jornadas/" + j + "/visible")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"visible\":false}")).andExpect(status().isConflict());

        accion(j, "cerrar").andExpect(status().isOk()).andExpect(jsonPath("$.jornada.estadoVisual").value("CERRADA"));

        resultado(p1, 2, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.partidos[0].estado").value("finalizado"))
                .andExpect(jsonPath("$.partidos[0].resultado").value("L"))
                .andExpect(jsonPath("$.jornada.partidosFinalizados").value(1));
        resultado(p1, 1, 1).andExpect(jsonPath("$.partidos[0].resultado").value("E")); // corrección
        assertThat(jdbc.queryForList("SELECT accion FROM bitacora_admin WHERE entidad = 'partidos' ORDER BY id_bitacora", String.class))
                .containsExactly("CAPTURAR_RESULTADO", "CORREGIR_RESULTADO");

        accion(j, "finalizar").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PARTIDOS_PENDIENTES"));
        mvc.perform(auth(post("/api/admin/partidos/" + p2 + "/suspender"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.partidos[1].estado").value("suspendido"));
        // Una suspensión se puede deshacer mientras la jornada no esté finalizada
        mvc.perform(auth(post("/api/admin/partidos/" + p2 + "/reactivar"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.partidos[1].estado").value("programado"));
        mvc.perform(auth(post("/api/admin/partidos/" + p2 + "/reactivar"))).andExpect(status().isConflict());
        mvc.perform(auth(post("/api/admin/partidos/" + p2 + "/suspender"))).andExpect(status().isOk());
        accion(j, "finalizar").andExpect(status().isOk()).andExpect(jsonPath("$.jornada.estadoVisual").value("FINALIZADA"));
        mvc.perform(auth(post("/api/admin/partidos/" + p2 + "/reactivar"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("JORNADA_FINALIZADA"));

        resultado(p1, 3, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("JORNADA_FINALIZADA"));

        // Ya se puede abrir la otra, y ocultar la finalizada
        abrir(otra).andExpect(status().isOk());
        mvc.perform(auth(put("/api/admin/jornadas/" + j + "/visible")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"visible\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.jornada.visible").value(false));
    }

    @Test
    void alEmpezarElPrimerPartidoQuedaEnJuegoYAceptaResultados() throws Exception {
        int j = idJornada(crear(50));
        int p = idUltimoPartido(agregar(j, eq.get(0), eq.get(1), enHoras(2)));
        abrir(j);
        jdbc.update("UPDATE partidos SET fecha_hora = now() - interval '10 minutes'"); // ya empezó

        mvc.perform(auth(get("/api/admin/jornadas/" + j)))
                .andExpect(jsonPath("$.jornada.estadoVisual").value("EN_JUEGO"))
                .andExpect(jsonPath("$.jornada.aceptaPronosticos").value(false));
        resultado(p, 0, 2).andExpect(status().isOk()).andExpect(jsonPath("$.partidos[0].resultado").value("V"));
        resultado(p, -1, 2).andExpect(status().isBadRequest());
    }

    @Test
    void eliminarYPermisos() throws Exception {
        int j = idJornada(crear(50));
        agregar(j, eq.get(0), eq.get(1), enHoras(24));
        mvc.perform(auth(delete("/api/admin/jornadas/" + j))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jornadas", Integer.class)).isZero();

        Usuario u = new Usuario("pepe", "Pepe", "pepe@correo.com", encoder.encode(PASS));
        u.setEstado(EstadoUsuario.aprobado);
        usuarios.save(u);
        String tokenUsuario = token("pepe");
        mvc.perform(get("/api/admin/jornadas").header("Authorization", "Bearer " + tokenUsuario))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/equipos").header("Authorization", "Bearer " + tokenUsuario))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + tokenAdmin);
    }

    private String token(String usuario) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"%s\",\"password\":\"%s\"}".formatted(usuario, PASS)))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions crear(int precio) throws Exception {
        return mvc.perform(auth(post("/api/admin/jornadas")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"precio\":" + precio + "}"));
    }

    private ResultActions agregar(int jornada, int local, int visita, Instant fecha) throws Exception {
        return mvc.perform(auth(post("/api/admin/jornadas/" + jornada + "/partidos")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idLocal\":%d,\"idVisitante\":%d,\"fechaHora\":\"%s\"}".formatted(local, visita, fecha)));
    }

    private ResultActions editarPartido(int partido, int local, int visita, Instant fecha) throws Exception {
        return mvc.perform(auth(put("/api/admin/partidos/" + partido)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idLocal\":%d,\"idVisitante\":%d,\"fechaHora\":\"%s\"}".formatted(local, visita, fecha)));
    }

    private ResultActions editarJornada(int jornada, int precio, int pct) throws Exception {
        return mvc.perform(auth(put("/api/admin/jornadas/" + jornada)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"precio\":%d,\"pctPremio\":%d}".formatted(precio, pct)));
    }

    private ResultActions abrir(int jornada) throws Exception {
        return accion(jornada, "abrir");
    }

    private ResultActions accion(int jornada, String accion) throws Exception {
        return mvc.perform(auth(post("/api/admin/jornadas/" + jornada + "/" + accion)));
    }

    private ResultActions resultado(int partido, int gl, int gv) throws Exception {
        return mvc.perform(auth(put("/api/admin/partidos/" + partido + "/resultado")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"golesLocal\":%d,\"golesVisitante\":%d}".formatted(gl, gv)));
    }

    private static Instant enHoras(int horas) {
        return Instant.now().plus(horas, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
    }

    private static int idJornada(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.jornada.id");
    }

    private static int idUltimoPartido(ResultActions r) throws Exception {
        List<Integer> ids = JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.partidos[*].id");
        return ids.stream().max(Integer::compare).orElseThrow();
    }
}
