package mx.quiniela.api.quinielas;

import java.math.BigDecimal;
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
@Table(name = "quinielas")
public class Quiniela {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_quiniela")
    private Integer id;

    /** Folio público (#1000, #1001...) que asigna la secuencia de la BD. */
    @Generated
    @Column(insertable = false, updatable = false)
    private Integer folio;

    @Column(name = "id_usuario", nullable = false, updatable = false)
    private Integer idUsuario;

    @Column(name = "id_jornada", nullable = false, updatable = false)
    private Integer idJornada;

    @Column(name = "numero_en_jornada", nullable = false, updatable = false)
    private short numero;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "estado_quiniela", nullable = false)
    private EstadoQuiniela estado = EstadoQuiniela.borrador;

    /** Precio congelado al crearla. */
    @Column(nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal monto;

    @Generated
    @Column(name = "creada_at", insertable = false, updatable = false)
    private Instant creadaAt;

    @Column(name = "enviada_at")
    private Instant enviadaAt;

    @Column(name = "actualizada_at", nullable = false)
    private Instant actualizadaAt;

    protected Quiniela() {
    }

    public Quiniela(Integer idUsuario, Integer idJornada, short numero, BigDecimal monto, Instant ahora) {
        this.idUsuario = idUsuario;
        this.idJornada = idJornada;
        this.numero = numero;
        this.monto = monto;
        this.actualizadaAt = ahora;
    }

    /** Borrador → pendiente de pago. */
    public void enviar(Instant ahora) {
        if (estado == EstadoQuiniela.borrador) {
            estado = EstadoQuiniela.pendiente_pago;
            enviadaAt = ahora;
        }
    }

    /** Se subió un comprobante: pendiente de pago (o rechazada) → en revisión. */
    public void pagoEnRevision(Instant ahora) {
        estado = EstadoQuiniela.en_revision;
        actualizadaAt = ahora;
    }

    public void pagoAprobado(Instant ahora) {
        estado = EstadoQuiniela.participando;
        actualizadaAt = ahora;
    }

    public void pagoRechazado(Instant ahora) {
        estado = EstadoQuiniela.rechazada;
        actualizadaAt = ahora;
    }

    public boolean puedeSubirPago() {
        return estado == EstadoQuiniela.pendiente_pago || estado == EstadoQuiniela.rechazada;
    }

    public boolean enviada() {
        return estado != EstadoQuiniela.borrador;
    }

    public void tocar(Instant ahora) {
        this.actualizadaAt = ahora;
    }

    public Integer getId() { return id; }
    public Integer getFolio() { return folio; }
    public Integer getIdUsuario() { return idUsuario; }
    public Integer getIdJornada() { return idJornada; }
    public short getNumero() { return numero; }
    public EstadoQuiniela getEstado() { return estado; }
    public BigDecimal getMonto() { return monto; }
    public Instant getCreadaAt() { return creadaAt; }
    public Instant getEnviadaAt() { return enviadaAt; }
    public Instant getActualizadaAt() { return actualizadaAt; }
}
