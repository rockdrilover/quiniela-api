package mx.quiniela.api.perfil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

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
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AvatarTest {

    private static final String PASS = "Futbol2026";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsuarioRepository usuarios;
    @Autowired PasswordEncoder encoder;
    @Autowired LimiteIpLogin limiteIp;
    @MockitoBean CorreoService correo;

    private String ana;

    @BeforeEach
    void preparar() throws Exception {
        limiteIp.limpiar();
        jdbc.execute("TRUNCATE usuarios, jornadas, partidos, quinielas, pronosticos, comprobantes, bitacora_admin RESTART IDENTITY CASCADE");
        Usuario u = new Usuario("ana", "Ana López", "ana@quiniela.mx", encoder.encode(PASS));
        // Un usuario pendiente también puede ponerse avatar (lo elige al registrarse)
        u.setEstado(EstadoUsuario.pendiente);
        usuarios.save(u);
        ana = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"ana\",\"password\":\"%s\"}".formatted(PASS)))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void presetYQuitar() throws Exception {
        mvc.perform(como(put("/api/perfil/avatar")).contentType(MediaType.APPLICATION_JSON).content("{\"preset\":\"trofeo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.avatar").value("preset:trofeo"));
        mvc.perform(como(put("/api/perfil/avatar")).contentType(MediaType.APPLICATION_JSON).content("{\"preset\":\"../x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("AVATAR_INVALIDO"));

        // Viaja en la sesión para el encabezado
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"ana\",\"password\":\"%s\"}".formatted(PASS)))
                .andExpect(jsonPath("$.usuario.avatar").value("preset:trofeo"));

        mvc.perform(como(delete("/api/perfil/avatar"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.avatar").doesNotExist());
    }

    @Test
    void subirFotoYVerlaPublica() throws Exception {
        subir("yo.txt", "no soy imagen".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TIPO_INVALIDO"));
        subir("yo.pdf", "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII)).andExpect(status().isBadRequest());

        String url = JsonPath.read(subir("yo.png", PNG).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.usuario.avatar");
        assertThat(url).matches("/api/avatares/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");

        // Sin token: se usa directo en <img>
        mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/api/avatares/2026/01/..%2F..%2Fcomprobantes.png")).andExpect(status().is4xxClientError());

        // Al cambiar a un preset, la foto vieja se borra
        mvc.perform(como(put("/api/perfil/avatar")).contentType(MediaType.APPLICATION_JSON).content("{\"preset\":\"balon\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isNotFound());
    }

    private ResultActions subir(String nombre, byte[] datos) throws Exception {
        return mvc.perform(multipart("/api/perfil/avatar")
                .file(new MockMultipartFile("archivo", nombre, "application/octet-stream", datos))
                .header("Authorization", "Bearer " + ana));
    }

    private MockHttpServletRequestBuilder como(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + ana);
    }
}
