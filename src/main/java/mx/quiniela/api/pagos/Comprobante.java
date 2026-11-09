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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TipoPago tipo = TipoPago.comprobante;

    /** Solo en comprobantes con archivo; null en pagos manuales. */
    @Column(name = "archivo_key", updatable = false, length = 500)
    private String archivoKey;

    @Column(name = "mime_type", updatable = false, length = 50)
    private String mimeType;

    @Column(name = "tamano_bytes", updatable = false)
    private Integer tamanoBytes;

    /** Nota del admin en pagos manuales ("efectivo", "transferencia"...). */
    @Column(length = 255)
    private String nota;

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

    /** El admin registra un pago recibido fuera de la app: nace aprobado y sin archivo. */
    public static Comprobante manual(Integer idQuiniela, Integer idAdmin, Instant ahora, String nota) {
        Comprobante c = new Comprobante();
        c.idQuiniela = idQuiniela;
        c.tipo = TipoPago.manual;
        c.nota = nota;
        c.aprobar(idAdmin, ahora);
        return c;
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
    public Integer getTamanoBytes() { return tamanoBytes; }
    public TipoPago getTipo() { return tipo; }
    public boolean esManual() { return tipo == TipoPago.manual; }
    public String getNota() { return nota; }
    public EstadoComprobante getEstado() { return estado; }
    public Instant getSubidoAt() { return subidoAt; }
    public Instant getVistoAt() { return vistoAt; }
    public Instant getRevisadoAt() { return revisadoAt; }
    public String getMotivoRechazo() { return motivoRechazo; }
}
