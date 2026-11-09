package mx.quiniela.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mx.quiniela.api.usuarios.Usuario;

/** Cuerpos de petición y respuesta del módulo de autenticación. */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** "usuario" acepta correo o nombre de usuario. */
    public record LoginRequest(
            @NotBlank(message = "Escribe tu correo o usuario") String usuario,
            @NotBlank(message = "Escribe tu contraseña") String password) {
    }

    public record RegistroRequest(
            @NotBlank(message = "Escribe un nombre de usuario")
            @Pattern(regexp = "^[A-Za-z0-9_.]{3,30}$",
                     message = "De 3 a 30 caracteres: letras, números, punto o guion bajo (sin espacios)") String username,
            @NotBlank(message = "Escribe tu nombre") @Size(max = 100, message = "Máximo 100 caracteres") String nombre,
            @NotBlank(message = "Escribe tu correo") @Email(message = "El correo no es válido")
            @Size(max = 254, message = "El correo es demasiado largo") String correo,
            @NotBlank(message = "Escribe una contraseña") String password,
            @NotBlank(message = "Confirma la contraseña") String confirmacion) {
    }

    public record RefreshRequest(@NotBlank(message = "Falta el refresh token") String refreshToken) {
    }

    public record CambioPasswordRequest(
            @NotBlank(message = "Escribe tu contraseña actual") String actual,
            @NotBlank(message = "Escribe la nueva contraseña") String nueva,
            @NotBlank(message = "Confirma la nueva contraseña") String confirmacion) {
    }

    public record RecuperarRequest(
            @NotBlank(message = "Escribe tu correo") @Email(message = "El correo no es válido") String correo) {
    }

    public record VerificarCodigoRequest(
            @NotBlank(message = "Escribe tu correo") @Email(message = "El correo no es válido") String correo,
            @NotBlank(message = "Escribe el código")
            @Pattern(regexp = "\\d{6}", message = "El código son 6 dígitos") String codigo) {
    }

    public record RestablecerRequest(
            @NotBlank(message = "Escribe tu correo") @Email(message = "El correo no es válido") String correo,
            @NotBlank(message = "Escribe el código")
            @Pattern(regexp = "\\d{6}", message = "El código son 6 dígitos") String codigo,
            @NotBlank(message = "Escribe la nueva contraseña") @Size(max = 72) String nueva,
            @NotBlank(message = "Confirma la nueva contraseña") String confirmacion) {
    }

    public record UsuarioDto(Integer id, String username, String nombre, String correo, String rol, String estado,
                             String avatar) {
        public static UsuarioDto de(Usuario u) {
            return new UsuarioDto(u.getId(), u.getUsername(), u.getNombre(), u.getCorreo(),
                    u.getRol().name(), u.getEstado().name(), u.getAvatarUrl());
        }
    }

    /** expiraEn = segundos de vida del accessToken. */
    public record TokensResponse(String accessToken, String refreshToken, long expiraEn, UsuarioDto usuario) {
    }
}
