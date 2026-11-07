package mx.quiniela.api.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.auth.dto.AuthDtos.RegistroRequest;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.correo.CorreoService;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/**
 * Registro público. El usuario nace con rol "usuario" y estado "pendiente":
 * puede iniciar sesión, pero no crear quinielas hasta que un admin lo apruebe.
 */
@Service
public class RegistroService {

    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwordEncoder;
    private final CorreoService correo;

    public RegistroService(UsuarioRepository usuarios, PasswordEncoder passwordEncoder, CorreoService correo) {
        this.usuarios = usuarios;
        this.passwordEncoder = passwordEncoder;
        this.correo = correo;
    }

    @Transactional
    public Usuario registrar(RegistroRequest req) {
        String username = req.username().trim();
        String nombre = req.nombre().trim().replaceAll("\\s+", " ");
        String correoUsuario = req.correo().trim();

        PasswordPolicy.validar(req.password(), req.confirmacion());
        if (usuarios.usernameOcupado(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EN_USO", "Ese nombre de usuario ya está en uso, elige otro");
        }
        if (usuarios.correoOcupado(correoUsuario)) {
            throw new ApiException(HttpStatus.CONFLICT, "CORREO_EN_USO",
                    "Ya existe una cuenta con ese correo. Inicia sesión o recupera tu contraseña");
        }
        Usuario u;
        try {
            u = usuarios.saveAndFlush(new Usuario(username, nombre, correoUsuario, passwordEncoder.encode(req.password())));
        } catch (DataIntegrityViolationException e) {
            // Dos registros simultáneos con el mismo dato: lo atrapa el UNIQUE de la BD
            throw new ApiException(HttpStatus.CONFLICT, "USUARIO_EN_USO", "El usuario o el correo ya están en uso");
        }
        correo.enviarBienvenida(u.getCorreo(), u.getNombre(), u.getUsername());
        usuarios.adminsActivos().forEach(a ->
                correo.enviarAvisoNuevoRegistro(a.getCorreo(), u.getUsername(), u.getNombre(), u.getCorreo()));
        return u;
    }
}
