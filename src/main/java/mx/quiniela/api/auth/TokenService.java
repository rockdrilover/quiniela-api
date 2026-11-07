package mx.quiniela.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import mx.quiniela.api.config.SeguridadProperties;
import mx.quiniela.api.usuarios.Usuario;

/** Genera tokens de acceso (JWT) y refresh tokens opacos. */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final SeguridadProperties props;

    public TokenService(JwtEncoder encoder, SeguridadProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    /** JWT corto (15 min por defecto). sub = id_usuario, claims: username, rol, estado. */
    public String crearAccessToken(Usuario u, Instant ahora) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("quiniela-api")
                .subject(String.valueOf(u.getId()))
                .issuedAt(ahora)
                .expiresAt(ahora.plus(props.accessTtl()))
                .claim("username", u.getUsername())
                .claim("rol", u.getRol().name())
                .claim("estado", u.getEstado().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** 256 bits aleatorios en base64url. Es lo que recibe el cliente; en BD va solo el hash. */
    public String nuevoRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String valor) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
