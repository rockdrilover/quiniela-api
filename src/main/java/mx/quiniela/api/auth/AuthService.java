package mx.quiniela.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.auth.dto.AuthDtos.TokensResponse;
import mx.quiniela.api.auth.dto.AuthDtos.UsuarioDto;
import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.config.SeguridadProperties;
import mx.quiniela.api.correo.CorreoService;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/** Login, renovación de sesión, logout y cambio de contraseña. */
@Service
public class AuthService {

    /** Hash de relleno: si el usuario no existe igual se corre BCrypt, para no revelar qué correos existen por tiempo de respuesta. */
    private final String hashRelleno;
    private final UsuarioRepository usuarios;
    private final SesionRepository sesiones;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final SeguridadProperties props;
    private final Clock clock;
    private final CorreoService correo;
    private final LimiteIpLogin limiteIp;

    public AuthService(UsuarioRepository usuarios, SesionRepository sesiones, PasswordEncoder passwordEncoder,
                       TokenService tokens, SeguridadProperties props, Clock clock, CorreoService correo,
                       LimiteIpLogin limiteIp) {
        this.correo = correo;
        this.limiteIp = limiteIp;
        this.usuarios = usuarios;
        this.sesiones = sesiones;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.props = props;
        this.clock = clock;
        this.hashRelleno = passwordEncoder.encode(tokens.nuevoRefreshToken());
    }

    /**
     * Login con dos frenos contra quien intenta adivinar contraseñas:
     *  - por cuenta: tras {@code loginMaxIntentos} fallos seguidos, la cuenta no puede entrar durante {@code loginBloqueo}
     *    (aunque después escriba la contraseña correcta). Recuperar la contraseña por correo quita el bloqueo.
     *  - por IP: tras {@code loginMaxFallosIp} fallos (en cualquier cuenta) esa IP espera {@code loginBloqueo}.
     * El contador se guarda aunque la petición termine en error (noRollbackFor).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokensResponse login(String usuarioOCorreo, String password, String userAgent, String ip) {
        limiteIp.esperaPara(ip).ifPresent(espera -> {
            throw ApiException.demasiadosIntentos(espera.toSeconds());
        });
        Instant ahora = clock.instant();
        Usuario u = usuarios.buscarPorCorreoOUsername(usuarioOCorreo.trim()).orElse(null);
        if (u != null && u.loginBloqueado(ahora)) {
            throw ApiException.demasiadosIntentos(Duration.between(ahora, u.getBloqueoLoginHasta()).toSeconds());
        }
        String hash = u != null ? u.getPasswordHash() : hashRelleno;
        boolean ok = passwordEncoder.matches(password, hash);
        if (u == null || !ok) {
            limiteIp.registrarFallo(ip);
            if (u != null) {
                u.registrarLoginFallido(props.loginMaxIntentos(), props.loginBloqueo(), ahora);
                if (u.loginBloqueado(ahora)) {
                    throw ApiException.demasiadosIntentos(props.loginBloqueo().toSeconds());
                }
            }
            throw ApiException.noAutorizado("CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos");
        }
        if (!u.puedeIniciarSesion()) {
            throw ApiException.prohibido("CUENTA_BLOQUEADA", "Tu cuenta está bloqueada. Contacta al administrador");
        }
        u.reiniciarIntentosLogin();
        u.setUltimoLogin(ahora);
        return emitirTokens(u, userAgent, ahora);
    }

    /**
     * Rota el refresh token: el usado se revoca y se entrega uno nuevo.
     * Si llega un token YA revocado, alguien lo copió: se cierran todas las sesiones del usuario.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokensResponse refrescar(String refreshToken, String userAgent) {
        Instant ahora = clock.instant();
        Sesion s = sesiones.findByTokenHash(TokenService.sha256(refreshToken))
                .orElseThrow(() -> ApiException.noAutorizado("SESION_INVALIDA", "Tu sesión expiró, vuelve a iniciar sesión"));
        if (s.getRevocadaAt() != null) {
            sesiones.revocarTodas(s.getIdUsuario(), ahora);
            throw ApiException.noAutorizado("SESION_INVALIDA", "Tu sesión expiró, vuelve a iniciar sesión");
        }
        if (!s.estaVigente(ahora)) {
            throw ApiException.noAutorizado("SESION_INVALIDA", "Tu sesión expiró, vuelve a iniciar sesión");
        }
        s.revocar(ahora);
        Usuario u = usuarios.findById(s.getIdUsuario())
                .filter(Usuario::puedeIniciarSesion)
                .orElseThrow(() -> ApiException.prohibido("CUENTA_BLOQUEADA", "Tu cuenta está bloqueada. Contacta al administrador"));
        return emitirTokens(u, userAgent, ahora);
    }

    @Transactional
    public void logout(String refreshToken) {
        sesiones.findByTokenHash(TokenService.sha256(refreshToken))
                .ifPresent(s -> s.revocar(clock.instant()));
    }

    /** Cambia la contraseña, cierra todas las sesiones (otros dispositivos) y entrega tokens nuevos para este. */
    @Transactional
    public TokensResponse cambiarPassword(Integer idUsuario, String actual, String nueva, String confirmacion,
                                          String userAgent) {
        Usuario u = usuarios.findById(idUsuario)
                .filter(Usuario::puedeIniciarSesion)
                .orElseThrow(() -> ApiException.noAutorizado("SESION_INVALIDA", "Tu sesión expiró, vuelve a iniciar sesión"));
        if (!passwordEncoder.matches(actual, u.getPasswordHash())) {
            throw ApiException.invalido("PASSWORD_ACTUAL_INCORRECTA", "La contraseña actual no es correcta");
        }
        PasswordPolicy.validar(nueva, confirmacion);
        if (passwordEncoder.matches(nueva, u.getPasswordHash())) {
            throw ApiException.invalido("PASSWORD_REPETIDA", "La nueva contraseña debe ser distinta a la actual");
        }
        Instant ahora = clock.instant();
        u.setPasswordHash(passwordEncoder.encode(nueva));
        sesiones.revocarTodas(u.getId(), ahora);
        correo.enviarAvisoCambioPassword(u.getCorreo(), u.getNombre());
        return emitirTokens(u, userAgent, ahora);
    }

    @Transactional(readOnly = true)
    public UsuarioDto perfil(Integer idUsuario) {
        return usuarios.findById(idUsuario)
                .filter(Usuario::puedeIniciarSesion)
                .map(UsuarioDto::de)
                .orElseThrow(() -> ApiException.noAutorizado("SESION_INVALIDA", "Tu sesión expiró, vuelve a iniciar sesión"));
    }

    /** Sesión para un usuario recién registrado. */
    @Transactional
    public TokensResponse iniciarSesion(Usuario u, String userAgent) {
        return emitirTokens(u, userAgent, clock.instant());
    }

    private TokensResponse emitirTokens(Usuario u, String userAgent, Instant ahora) {
        String refresh = tokens.nuevoRefreshToken();
        sesiones.save(new Sesion(u.getId(), TokenService.sha256(refresh), userAgent, ahora.plus(props.refreshTtl())));
        String access = tokens.crearAccessToken(u, ahora);
        return new TokensResponse(access, refresh, props.accessTtl().toSeconds(), UsuarioDto.de(u));
    }
}
