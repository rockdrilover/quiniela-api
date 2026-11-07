package mx.quiniela.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import mx.quiniela.api.correo.CorreoService;
import mx.quiniela.api.usuarios.EstadoUsuario;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/** Pruebas de punta a punta contra PostgreSQL (base quiniela_test). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlujoTest {

    private static final String PASS = "Futbol2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    @BeforeEach
    void limpiar() {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios RESTART IDENTITY CASCADE");
        usuarios.save(new Usuario("juan_perez", "Juan Pérez", "juan@correo.com", encoder.encode(PASS)));
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginConCorreoOUsernameSinImportarMayusculas() throws Exception {
        login("JUAN@correo.com", PASS).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiraEn").value(900))
                .andExpect(jsonPath("$.usuario.username").value("juan_perez"))
                .andExpect(jsonPath("$.usuario.rol").value("usuario"))
                .andExpect(jsonPath("$.usuario.estado").value("pendiente"));
        login("Juan_Perez", PASS).andExpect(status().isOk());
    }

    @Test
    void loginIncorrectoDaMismoErrorExistaONoElUsuario() throws Exception {
        login("juan@correo.com", "otra1234").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
        login("nadie@correo.com", PASS).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
    }

    @Test
    void usuarioBloqueadoNoEntra() throws Exception {
        Usuario u = usuarios.buscarPorCorreo("juan@correo.com").orElseThrow();
        u.setEstado(EstadoUsuario.bloqueado);
        usuarios.save(u);
        login("juan@correo.com", PASS).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CUENTA_BLOQUEADA"));
    }

    @Test
    void camposVaciosDan400ConDetallePorCampo() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"usuario\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.usuario").exists())
                .andExpect(jsonPath("$.campos.password").exists());
    }

    @Test
    void yoRequiereTokenValido() throws Exception {
        mvc.perform(get("/api/auth/yo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/yo").header("Authorization", "Bearer basura")).andExpect(status().isUnauthorized());

        String access = campo(login("juan_perez", PASS), "$.accessToken");
        mvc.perform(get("/api/auth/yo").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value("juan@correo.com"));
    }

    // ------------------------------------------------------- límite de intentos

    @Test
    void cincoFallosSeguidosBloqueanLaCuentaQuinceMinutos() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("juan_perez", "mala1234").andExpect(status().isUnauthorized());
        }
        login("juan_perez", "mala1234").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("DEMASIADOS_INTENTOS"))
                .andExpect(jsonPath("$.reintentarEn").value(900))
                .andExpect(header().string("Retry-After", "900"));

        // Bloqueada: ni con la contraseña correcta entra
        login("juan_perez", PASS).andExpect(status().isTooManyRequests());
        login("JUAN@correo.com", PASS).andExpect(status().isTooManyRequests());

        // Pasan los 15 minutos
        jdbc.update("UPDATE usuarios SET bloqueo_login_hasta = now() - interval '1 second'");
        login("juan_perez", PASS).andExpect(status().isOk());
    }

    @Test
    void unLoginCorrectoReiniciaElContador() throws Exception {
        for (int i = 0; i < 4; i++) login("juan_perez", "mala1234").andExpect(status().isUnauthorized());
        login("juan_perez", PASS).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login("juan_perez", "mala1234").andExpect(status().isUnauthorized());
        login("juan_perez", PASS).andExpect(status().isOk());
    }

    @Test
    void recuperarPorCorreoQuitaElBloqueo() throws Exception {
        for (int i = 0; i < 5; i++) login("juan_perez", "mala1234");
        login("juan_perez", PASS).andExpect(status().isTooManyRequests());

        solicitar("juan@correo.com");
        restablecer("juan@correo.com", codigoEnviado(), "Recuperada9", "Recuperada9").andExpect(status().isNoContent());
        login("juan_perez", "Recuperada9").andExpect(status().isOk());
    }

    @Test
    void muchasCuentasFallidasDesdeLaMismaIpLaFrenan() throws Exception {
        for (int i = 0; i < 20; i++) {
            login("noexiste" + i + "@correo.com", "mala1234").andExpect(status().isUnauthorized());
        }
        login("juan_perez", PASS).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("DEMASIADOS_INTENTOS"));
        // Otra IP no se ve afectada
        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\":\"juan_perez\",\"password\":\"%s\"}".formatted(PASS)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------- refresh / logout

    @Test
    void refreshRotaElTokenYDetectaReuso() throws Exception {
        String r1 = campo(login("juan_perez", PASS), "$.refreshToken");

        String r2 = campo(refresh(r1).andExpect(status().isOk()), "$.refreshToken");
        assertThat(r2).isNotEqualTo(r1);

        // Reusar r1 (ya rotado) = posible robo: se rechaza y se cierran TODAS las sesiones
        refresh(r1).andExpect(status().isUnauthorized());
        refresh(r2).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidaElRefreshToken() throws Exception {
        String r = campo(login("juan_perez", PASS), "$.refreshToken");
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r + "\"}")).andExpect(status().isNoContent());
        refresh(r).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------- cambio de contraseña

    @Test
    void cambioDePassword() throws Exception {
        ResultActions sesion = login("juan_perez", PASS);
        String access = campo(sesion, "$.accessToken");
        String refreshViejo = campo(sesion, "$.refreshToken");

        cambiar(access, "incorrecta1", "Nueva2026x", "Nueva2026x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_ACTUAL_INCORRECTA"));
        cambiar(access, PASS, "corta1", "corta1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_DEBIL"));
        cambiar(access, PASS, "sinnumeros", "sinnumeros").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_DEBIL"));
        cambiar(access, PASS, "Nueva2026x", "Otra2026x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_NO_COINCIDE"));
        cambiar(access, PASS, PASS, PASS).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_REPETIDA"));

        cambiar(access, PASS, "Nueva2026x", "Nueva2026x").andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        login("juan_perez", PASS).andExpect(status().isUnauthorized());
        login("juan_perez", "Nueva2026x").andExpect(status().isOk());
        refresh(refreshViejo).andExpect(status().isUnauthorized()); // otras sesiones cerradas
        verify(correo).enviarAvisoCambioPassword("juan@correo.com", "Juan Pérez");
    }

    // ------------------------------------------------------- recuperación por correo

    @Test
    void recuperacionCompletaPorCorreo() throws Exception {
        String refreshViejo = campo(login("juan_perez", PASS), "$.refreshToken");

        solicitar("Juan@Correo.com").andExpect(status().isAccepted());
        String codigo = codigoEnviado();

        String incorrecto = codigo.equals("000000") ? "111111" : "000000";
        verificar("juan@correo.com", incorrecto).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("CODIGO_INVALIDO"));
        verificar("juan@correo.com", codigo).andExpect(status().isNoContent());

        restablecer("juan@correo.com", codigo, "Recuperada9", "Recuperada9").andExpect(status().isNoContent());

        login("juan_perez", "Recuperada9").andExpect(status().isOk());
        login("juan_perez", PASS).andExpect(status().isUnauthorized());
        refresh(refreshViejo).andExpect(status().isUnauthorized());
        // El código ya se usó
        restablecer("juan@correo.com", codigo, "OtraVez99", "OtraVez99").andExpect(status().isBadRequest());
    }

    @Test
    void correoInexistenteRespondeIgualYNoEnviaNada() throws Exception {
        solicitar("nadie@correo.com").andExpect(status().isAccepted());
        verify(correo, never()).enviarCodigoRecuperacion(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void cincoIntentosFallidosInvalidanElCodigo() throws Exception {
        solicitar("juan@correo.com");
        String codigo = codigoEnviado();
        String incorrecto = codigo.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verificar("juan@correo.com", incorrecto).andExpect(status().isBadRequest());
        }
        verificar("juan@correo.com", codigo).andExpect(status().isBadRequest());
    }

    @Test
    void soloElUltimoCodigoEsValidoYHayLimiteDeEnvios() throws Exception {
        solicitar("juan@correo.com");
        String primero = codigoEnviado();
        solicitar("juan@correo.com");
        solicitar("juan@correo.com");
        solicitar("juan@correo.com"); // 4.º en 15 min: no se envía

        ArgumentCaptor<String> cod = ArgumentCaptor.forClass(String.class);
        verify(correo, times(3)).enviarCodigoRecuperacion(eq("juan@correo.com"), anyString(), cod.capture(), anyLong());
        String ultimo = cod.getAllValues().get(2);

        if (!primero.equals(ultimo)) {
            verificar("juan@correo.com", primero).andExpect(status().isBadRequest());
        }
        verificar("juan@correo.com", ultimo).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions login(String usuario, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"%s\",\"password\":\"%s\"}".formatted(usuario, password)));
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}"));
    }

    private ResultActions cambiar(String access, String actual, String nueva, String conf) throws Exception {
        return mvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actual\":\"%s\",\"nueva\":\"%s\",\"confirmacion\":\"%s\"}".formatted(actual, nueva, conf)));
    }

    private ResultActions solicitar(String correoUsuario) throws Exception {
        return mvc.perform(post("/api/auth/recuperar").contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"" + correoUsuario + "\"}"));
    }

    private ResultActions verificar(String correoUsuario, String codigo) throws Exception {
        return mvc.perform(post("/api/auth/recuperar/verificar").contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"%s\",\"codigo\":\"%s\"}".formatted(correoUsuario, codigo)));
    }

    private ResultActions restablecer(String correoUsuario, String codigo, String nueva, String conf) throws Exception {
        return mvc.perform(post("/api/auth/recuperar/restablecer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"%s\",\"codigo\":\"%s\",\"nueva\":\"%s\",\"confirmacion\":\"%s\"}"
                        .formatted(correoUsuario, codigo, nueva, conf)));
    }

    private String codigoEnviado() {
        ArgumentCaptor<String> cod = ArgumentCaptor.forClass(String.class);
        verify(correo).enviarCodigoRecuperacion(eq("juan@correo.com"), eq("Juan Pérez"), cod.capture(), eq(15L));
        assertThat(cod.getValue()).matches("\\d{6}");
        return cod.getValue();
    }

    private static String campo(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }
}
