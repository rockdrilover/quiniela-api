package mx.quiniela.api.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Código de 6 dígitos enviado por correo. Solo se guarda su HMAC. */
@Entity
@Table(name = "codigos_recuperacion")
public class CodigoRecuperacion {

    public static final int MAX_INTENTOS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_codigo")
    private Long id;

    @Column(name = "id_usuario", nullable = false)
    private Integer idUsuario;

    @Column(name = "codigo_hash", nullable = false)
    private String codigoHash;

    @Column(nullable = false)
    private short intentos;

    @Column(name = "creado_at", insertable = false, updatable = false)
    private Instant creadoAt;

    @Column(name = "expira_at", nullable = false)
    private Instant expiraAt;

    @Column(name = "usado_at")
    private Instant usadoAt;

    protected CodigoRecuperacion() {
    }

    public CodigoRecuperacion(Integer idUsuario, String codigoHash, Instant expiraAt) {
        this.idUsuario = idUsuario;
        this.codigoHash = codigoHash;
        this.expiraAt = expiraAt;
    }

    public boolean estaVigente(Instant ahora) {
        return usadoAt == null && intentos < MAX_INTENTOS && expiraAt.isAfter(ahora);
    }

    public void registrarIntentoFallido() {
        if (intentos < MAX_INTENTOS) intentos++;
    }

    public void marcarUsado(Instant ahora) {
        usadoAt = ahora;
    }

    public Long getId() { return id; }
    public Integer getIdUsuario() { return idUsuario; }
    public String getCodigoHash() { return codigoHash; }
    public short getIntentos() { return intentos; }
    public Instant getUsadoAt() { return usadoAt; }
}
