package mx.quiniela.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class RegistroYAprobacionTest {

    private static final String PASS = "Futbol2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    private String tokenAdmin;

    @BeforeEach
    void preparar() throws Exception {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios RESTART IDENTITY CASCADE");
        Usuario admin = new Usuario("admin", "Administrador", "admin@quiniela.mx", encoder.encode(PASS));
        admin.setRol(Rol.admin);
        admin.setEstado(EstadoUsuario.aprobado);
        usuarios.save(admin);
        tokenAdmin = campo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"admin\",\"password\":\"%s\"}".formatted(PASS))), "$.accessToken");
    }

    // ---------------------------------------------------------------- registro

    @Test
    void registroCreaUsuarioPendienteConSesionYAvisa() throws Exception {
        registrar("pepe.gol", "  Pepe   Gómez ", "Pepe@Correo.com", PASS, PASS)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.usuario.username").value("pepe.gol"))
                .andExpect(jsonPath("$.usuario.nombre").value("Pepe Gómez"))
                .andExpect(jsonPath("$.usuario.rol").value("usuario"))
                .andExpect(jsonPath("$.usuario.estado").value("pendiente"));

        verify(correo).enviarBienvenida("Pepe@Correo.com", "Pepe Gómez", "pepe.gol");
        verify(correo).enviarAvisoNuevoRegistro("admin@quiniela.mx", "pepe.gol", "Pepe Gómez", "Pepe@Correo.com");
        // Puede entrar aunque esté pendiente
        login("pepe@correo.com", PASS).andExpect(status().isOk());
    }

    @Test
    void usernameYCorreoUnicosSinImportarMayusculas() throws Exception {
        registrar("pepe", "Pepe", "pepe@correo.com", PASS, PASS).andExpect(status().isCreated());
        registrar("PEPE", "Otro", "otro@correo.com", PASS, PASS).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("USERNAME_EN_USO"));
        registrar("otro", "Otro", "PEPE@correo.com", PASS, PASS).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CORREO_EN_USO"));
    }

    @Test
    void validacionesDelRegistro() throws Exception {
        registrar("con espacio", "Pepe", "pepe@correo.com", PASS, PASS).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.username").exists());
        registrar("ab", "Pepe", "pepe@correo.com", PASS, PASS).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.username").exists());
        registrar("pepe", "Pepe", "no-es-correo", PASS, PASS).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.correo").exists());
        registrar("pepe", "x".repeat(101), "pepe@correo.com", PASS, PASS).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombre").exists());
        registrar("pepe", "Pepe", "pepe@correo.com", "corta1", "corta1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_DEBIL"));
        registrar("pepe", "Pepe", "pepe@correo.com", PASS, "Otra2026").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_NO_COINCIDE"));
        assertThat(usuarios.count()).isEqualTo(1); // solo el admin
    }

    // ---------------------------------------------------------------- admin

    @Test
    void adminListaPendientesYAprueba() throws Exception {
        registrar("pepe", "Pepe", "pepe@correo.com", PASS, PASS);
        registrar("luis", "Luis", "luis@correo.com", PASS, PASS);

        mvc.perform(get("/api/admin/usuarios?estado=pendiente").header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.elementos[0].username").value("pepe")) // el más antiguo primero
                .andExpect(jsonPath("$.elementos[1].username").value("luis"));

        Integer idPepe = usuarios.buscarPorCorreo("pepe@correo.com").orElseThrow().getId();
        admin("aprobar", idPepe).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("aprobado"))
                .andExpect(jsonPath("$.aprobadoAt").isNotEmpty());
        verify(correo).enviarCuentaAprobada("pepe@correo.com", "Pepe");

        admin("aprobar", idPepe).andExpect(status().isConflict()); // ya no está pendiente

        assertThat(jdbc.queryForObject(
                "SELECT accion || ':' || (datos_despues->>'estado') FROM bitacora_admin WHERE id_entidad = ?",
                String.class, idPepe)).isEqualTo("APROBAR_USUARIO:aprobado");

        // Al renovar sesión el token ya trae estado aprobado
        String refresh = campo(login("pepe", PASS), "$.refreshToken");
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(jsonPath("$.usuario.estado").value("aprobado"));
    }

    @Test
    void bloquearCierraSesionesYDesbloquearRegresa() throws Exception {
        registrar("pepe", "Pepe", "pepe@correo.com", PASS, PASS);
        Integer idPepe = usuarios.buscarPorCorreo("pepe@correo.com").orElseThrow().getId();
        String refresh = campo(login("pepe", PASS), "$.refreshToken");

        admin("bloquear", idPepe).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("bloqueado"));
        login("pepe", PASS).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}")).andExpect(status().isUnauthorized());

        admin("desbloquear", idPepe).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("aprobado"));
        login("pepe", PASS).andExpect(status().isOk());
    }

    @Test
    void reglasDeBloqueo() throws Exception {
        admin("bloquear", 1).andExpect(status().isBadRequest()); // a sí mismo
        admin("aprobar", 999).andExpect(status().isNotFound());
    }

    @Test
    void soloElAdminEntraAlPanel() throws Exception {
        String tokenUsuario = campo(registrar("pepe", "Pepe", "pepe@correo.com", PASS, PASS), "$.accessToken");
        mvc.perform(get("/api/admin/usuarios").header("Authorization", "Bearer " + tokenUsuario))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/usuarios/2/aprobar").header("Authorization", "Bearer " + tokenUsuario))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/usuarios")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions registrar(String username, String nombre, String correoU, String pass, String conf)
            throws Exception {
        return mvc.perform(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"nombre\":\"%s\",\"correo\":\"%s\",\"password\":\"%s\",\"confirmacion\":\"%s\"}"
                        .formatted(username, nombre, correoU, pass, conf)));
    }

    private ResultActions login(String usuario, String pass) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"%s\",\"password\":\"%s\"}".formatted(usuario, pass)));
    }

    private ResultActions admin(String accion, Integer id) throws Exception {
        return mvc.perform(put("/api/admin/usuarios/" + id + "/" + accion)
                .header("Authorization", "Bearer " + tokenAdmin));
    }

    private static String campo(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }
}
