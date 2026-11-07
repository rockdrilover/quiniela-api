package mx.quiniela.api.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros de seguridad (prefijo "app.seguridad").
 *
 * @param jwtSecreto       llave HMAC para firmar los JWT y los códigos de recuperación (mín. 32 bytes)
 * @param accessTtl        vida del token de acceso
 * @param refreshTtl       vida del refresh token (sesión)
 * @param codigoTtl        vida del código de recuperación por correo
 * @param codigosPorVentana máximo de códigos que se envían a un usuario dentro de codigoTtl
 * @param loginMaxIntentos  intentos fallidos seguidos antes de bloquear la cuenta
 * @param loginBloqueo      cuánto dura el bloqueo de la cuenta
 * @param loginMaxFallosIp  intentos fallidos permitidos desde una misma IP dentro de loginBloqueo (cualquier cuenta)
 */
@ConfigurationProperties("app.seguridad")
public record SeguridadProperties(
        String jwtSecreto,
        Duration accessTtl,
        Duration refreshTtl,
        Duration codigoTtl,
        int codigosPorVentana,
        int loginMaxIntentos,
        Duration loginBloqueo,
        int loginMaxFallosIp) {

    public SeguridadProperties {
        if (jwtSecreto == null || jwtSecreto.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.seguridad.jwt-secreto (JWT_SECRET) debe tener al menos 32 caracteres");
        }
        if (accessTtl == null) accessTtl = Duration.ofMinutes(15);
        if (refreshTtl == null) refreshTtl = Duration.ofDays(30);
        if (codigoTtl == null) codigoTtl = Duration.ofMinutes(15);
        if (codigosPorVentana <= 0) codigosPorVentana = 3;
        if (loginMaxIntentos <= 0) loginMaxIntentos = 5;
        if (loginBloqueo == null) loginBloqueo = Duration.ofMinutes(15);
        if (loginMaxFallosIp <= 0) loginMaxFallosIp = 20;
    }
}
