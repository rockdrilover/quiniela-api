package mx.quiniela.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.config.SeguridadProperties;
import mx.quiniela.api.correo.CorreoService;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/**
 * "¿Olvidaste tu contraseña?" en 3 pasos (pantallas de la especificación):
 *  1. solicitar(correo)              -> se envía un código de 6 dígitos (vence en 15 min)
 *  2. verificar(correo, código)      -> confirma que el código es correcto (no lo consume)
 *  3. restablecer(correo, código, nueva, confirmación) -> cambia la contraseña y consume el código
 *
 * Protecciones: máximo 5 intentos por código, solo el último código es válido,
 * máximo 3 códigos por cada 15 min, la respuesta del paso 1 es igual exista o no el correo,
 * y en BD se guarda un HMAC del código (no el código).
 */
@Service
public class RecuperacionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODIGO_INCORRECTO = "El código es incorrecto o ya venció. Solicita uno nuevo si es necesario";

    private final UsuarioRepository usuarios;
    private final CodigoRecuperacionRepository codigos;
    private final SesionRepository sesiones;
    private final PasswordEncoder passwordEncoder;
    private final CorreoService correo;
    private final SeguridadProperties props;
    private final SecretKey llave;
    private final Clock clock;

    public RecuperacionService(UsuarioRepository usuarios, CodigoRecuperacionRepository codigos,
                               SesionRepository sesiones, PasswordEncoder passwordEncoder, CorreoService correo,
                               SeguridadProperties props, SecretKey jwtKey, Clock clock) {
        this.usuarios = usuarios;
        this.codigos = codigos;
        this.sesiones = sesiones;
        this.passwordEncoder = passwordEncoder;
        this.correo = correo;
        this.props = props;
        this.llave = jwtKey;
        this.clock = clock;
    }

    @Transactional
    public void solicitar(String correoUsuario) {
        Usuario u = usuarios.buscarPorCorreo(correoUsuario.trim()).orElse(null);
        if (u == null || !u.puedeIniciarSesion()) {
            return; // misma respuesta que si existiera
        }
        Instant ahora = clock.instant();
        long recientes = codigos.countByIdUsuarioAndCreadoAtAfter(u.getId(), ahora.minus(props.codigoTtl()));
        if (recientes >= props.codigosPorVentana()) {
            return; // demasiadas solicitudes: no se envía otro correo
        }
        codigos.anularPendientes(u.getId(), ahora);
        String codigo = "%06d".formatted(RANDOM.nextInt(1_000_000));
        codigos.save(new CodigoRecuperacion(u.getId(), hmac(u.getId(), codigo), ahora.plus(props.codigoTtl())));
        correo.enviarCodigoRecuperacion(u.getCorreo(), u.getNombre(), codigo, props.codigoTtl().toMinutes());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verificar(String correoUsuario, String codigo) {
        validarCodigo(correoUsuario, codigo);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void restablecer(String correoUsuario, String codigo, String nueva, String confirmacion) {
        PasswordPolicy.validar(nueva, confirmacion);
        Instant ahora = clock.instant();
        CodigoRecuperacion c = validarCodigo(correoUsuario, codigo);
        Usuario u = usuarios.findById(c.getIdUsuario()).orElseThrow();
        c.marcarUsado(ahora);
        u.setPasswordHash(passwordEncoder.encode(nueva));
        u.reiniciarIntentosLogin(); // quien recupera por correo demuestra ser el dueño: se quita el bloqueo
        sesiones.revocarTodas(u.getId(), ahora);
        correo.enviarAvisoCambioPassword(u.getCorreo(), u.getNombre());
    }

    /** Devuelve el código vigente si coincide; si no, suma un intento fallido y lanza 400. */
    private CodigoRecuperacion validarCodigo(String correoUsuario, String codigo) {
        Instant ahora = clock.instant();
        Usuario u = usuarios.buscarPorCorreo(correoUsuario.trim())
                .filter(Usuario::puedeIniciarSesion)
                .orElseThrow(() -> ApiException.invalido("CODIGO_INVALIDO", CODIGO_INCORRECTO));
        CodigoRecuperacion c = codigos.findFirstByIdUsuarioAndUsadoAtIsNullOrderByIdDesc(u.getId())
                .filter(x -> x.estaVigente(ahora))
                .orElseThrow(() -> ApiException.invalido("CODIGO_INVALIDO", CODIGO_INCORRECTO));
        boolean coincide = MessageDigest.isEqual(
                c.getCodigoHash().getBytes(StandardCharsets.US_ASCII),
                hmac(u.getId(), codigo).getBytes(StandardCharsets.US_ASCII));
        if (!coincide) {
            c.registrarIntentoFallido();
            codigos.save(c);
            throw ApiException.invalido("CODIGO_INVALIDO", CODIGO_INCORRECTO);
        }
        return c;
    }

    private String hmac(Integer idUsuario, String codigo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(llave);
            byte[] r = mac.doFinal(("recuperacion:" + idUsuario + ":" + codigo).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(r);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
