package mx.quiniela.api.usuarios;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea el primer administrador al arrancar, solo si todavía no existe ninguno
 * y están definidas ADMIN_USERNAME, ADMIN_CORREO y ADMIN_PASSWORD.
 * Después de crearlo, esas variables se pueden quitar.
 */
@Component
public class AdminInicial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInicial.class);

    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String correo;
    private final String password;

    public AdminInicial(UsuarioRepository usuarios, PasswordEncoder passwordEncoder,
                        @Value("${app.admin-inicial.username:}") String username,
                        @Value("${app.admin-inicial.correo:}") String correo,
                        @Value("${app.admin-inicial.password:}") String password) {
        this.usuarios = usuarios;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.correo = correo;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() || correo.isBlank() || password.isBlank() || usuarios.existsByRol(Rol.admin)) {
            return;
        }
        Usuario admin = new Usuario(username, "Administrador", correo, passwordEncoder.encode(password));
        admin.setRol(Rol.admin);
        admin.setEstado(EstadoUsuario.aprobado);
        usuarios.save(admin);
        log.info("Administrador inicial creado: {}", username);
    }
}
