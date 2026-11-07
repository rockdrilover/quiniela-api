package mx.quiniela.api.pagos;

import java.time.Instant;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "comprobantes")
public class Comprobante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_comprobante")
    private Integer id;

    @Column(name = "id_quiniela", nullable = false, updatable = false)
    private Integer idQuiniela;

    @Column(name = "archivo_key", nullable = false, updatable = false, length = 500)
    private String archivoKey;

    @Column(name = "mime_type", nullable = false, updatable = false, length = 50)
    private String mimeType;

    @Column(name = "tamano_bytes", nullable = false, updatable = false)
    private int tamanoBytes;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "estado_comprobante", nullable = false)
    private EstadoComprobante estado = EstadoComprobante.en_revision;

    @Generated
    @Column(name = "subido_at", insertable = false, updatable = false)
    private Instant subidoAt;

    @Column(name = "visto_por")
    private Integer vistoPor;

    @Column(name = "visto_at")
    private Instant vistoAt;

    @Column(name = "revisado_por")
    private Integer revisadoPor;

    @Column(name = "revisado_at")
    private Instant revisadoAt;

    @Column(name = "motivo_rechazo", length = 255)
    private String motivoRechazo;

    protected Comprobante() {
    }

    public Comprobante(Integer idQuiniela, String archivoKey, String mimeType, int tamanoBytes) {
        this.idQuiniela = idQuiniela;
        this.archivoKey = archivoKey;
        this.mimeType = mimeType;
        this.tamanoBytes = tamanoBytes;
    }

    /** El admin abrió el archivo: a partir de aquí puede validarlo (regla de no validar a ciegas). */
    public boolean marcarVisto(Integer idAdmin, Instant ahora) {
        if (vistoAt != null) return false;
        vistoPor = idAdmin;
        vistoAt = ahora;
        return true;
    }

    public void aprobar(Integer idAdmin, Instant ahora) {
        estado = EstadoComprobante.aprobado;
        revisadoPor = idAdmin;
        revisadoAt = ahora;
    }

    public void rechazar(Integer idAdmin, Instant ahora, String motivo) {
        estado = EstadoComprobante.rechazado;
        revisadoPor = idAdmin;
        revisadoAt = ahora;
        motivoRechazo = motivo;
    }

    public Integer getId() { return id; }
    public Integer getIdQuiniela() { return idQuiniela; }
    public String getArchivoKey() { return archivoKey; }
    public String getMimeType() { return mimeType; }
    public int getTamanoBytes() { return tamanoBytes; }
    public EstadoComprobante getEstado() { return estado; }
    public Instant getSubidoAt() { return subidoAt; }
    public Instant getVistoAt() { return vistoAt; }
    public Instant getRevisadoAt() { return revisadoAt; }
    public String getMotivoRechazo() { return motivoRechazo; }
}
