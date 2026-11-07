package mx.quiniela.api.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import mx.quiniela.api.auth.dto.AuthDtos.CambioPasswordRequest;
import mx.quiniela.api.auth.dto.AuthDtos.LoginRequest;
import mx.quiniela.api.auth.dto.AuthDtos.RecuperarRequest;
import mx.quiniela.api.auth.dto.AuthDtos.RefreshRequest;
import mx.quiniela.api.auth.dto.AuthDtos.RegistroRequest;
import mx.quiniela.api.auth.dto.AuthDtos.RestablecerRequest;
import mx.quiniela.api.auth.dto.AuthDtos.TokensResponse;
import mx.quiniela.api.auth.dto.AuthDtos.UsuarioDto;
import mx.quiniela.api.auth.dto.AuthDtos.VerificarCodigoRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final RecuperacionService recuperacion;
    private final RegistroService registro;

    public AuthController(AuthService auth, RecuperacionService recuperacion, RegistroService registro) {
        this.auth = auth;
        this.recuperacion = recuperacion;
        this.registro = registro;
    }

    @PostMapping("/login")
    public TokensResponse login(@Valid @RequestBody LoginRequest req,
                                @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua,
                                HttpServletRequest request) {
        return auth.login(req.usuario(), req.password(), ua, request.getRemoteAddr());
    }

    /** Crea la cuenta (estado "pendiente") y deja la sesión iniciada. 201 · 409 si el usuario/correo ya existe. */
    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    public TokensResponse registro(@Valid @RequestBody RegistroRequest req,
                                   @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua) {
        return auth.iniciarSesion(registro.registrar(req), ua);
    }

    @PostMapping("/refresh")
    public TokensResponse refresh(@Valid @RequestBody RefreshRequest req,
                                  @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua) {
        return auth.refrescar(req.refreshToken(), ua);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
    }

    @GetMapping("/yo")
    public UsuarioDto yo(@AuthenticationPrincipal Jwt jwt) {
        return auth.perfil(Integer.valueOf(jwt.getSubject()));
    }

    @PutMapping("/password")
    public TokensResponse cambiarPassword(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody CambioPasswordRequest req,
                                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua) {
        return auth.cambiarPassword(Integer.valueOf(jwt.getSubject()), req.actual(), req.nueva(), req.confirmacion(), ua);
    }

    /** Paso 1: siempre responde 202, exista o no el correo. */
    @PostMapping("/recuperar")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void recuperar(@Valid @RequestBody RecuperarRequest req) {
        recuperacion.solicitar(req.correo());
    }

    /** Paso 2: 204 si el código es correcto, 400 si no. */
    @PostMapping("/recuperar/verificar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verificar(@Valid @RequestBody VerificarCodigoRequest req) {
        recuperacion.verificar(req.correo(), req.codigo());
    }

    /** Paso 3: cambia la contraseña y cierra todas las sesiones. */
    @PostMapping("/recuperar/restablecer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restablecer(@Valid @RequestBody RestablecerRequest req) {
        recuperacion.restablecer(req.correo(), req.codigo(), req.nueva(), req.confirmacion());
    }
}
