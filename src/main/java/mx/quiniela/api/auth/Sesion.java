package mx.quiniela.api.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un refresh token emitido. Solo se guarda su hash SHA-256. */
@Entity
@Table(name = "sesiones")
public class Sesion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_sesion")
    private Long id;

    @Column(name = "id_usuario", nullable = false)
    private Integer idUsuario;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "creada_at", insertable = false, updatable = false)
    private Instant creadaAt;

    @Column(name = "expira_at", nullable = false)
    private Instant expiraAt;

    @Column(name = "revocada_at")
    private Instant revocadaAt;

    protected Sesion() {
    }

    public Sesion(Integer idUsuario, String tokenHash, String userAgent, Instant expiraAt) {
        this.idUsuario = idUsuario;
        this.tokenHash = tokenHash;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length()));
        this.expiraAt = expiraAt;
    }

    public boolean estaVigente(Instant ahora) {
        return revocadaAt == null && expiraAt.isAfter(ahora);
    }

    public void revocar(Instant ahora) {
        if (revocadaAt == null) revocadaAt = ahora;
    }

    public Long getId() { return id; }
    public Integer getIdUsuario() { return idUsuario; }
    public Instant getRevocadaAt() { return revocadaAt; }
    public String getUserAgent() { return userAgent; }
}
