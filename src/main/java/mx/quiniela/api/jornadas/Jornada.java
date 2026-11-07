package mx.quiniela.api.jornadas;

import java.math.BigDecimal;
import java.time.Instant;

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
@Table(name = "jornadas")
public class Jornada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_jornada")
    private Integer id;

    @Column(name = "id_temporada", nullable = false)
    private Integer idTemporada;

    @Column(name = "numero_jornada", nullable = false)
    private Integer numero;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "estado_jornada", nullable = false)
    private EstadoJornada estado = EstadoJornada.borrador;

    @Column(nullable = false)
    private boolean visible;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precio;

    @Column(name = "pct_premio", nullable = false, precision = 5, scale = 2)
    private BigDecimal pctPremio;

    @Column(name = "bolsa_arrastrada", nullable = false, precision = 12, scale = 2)
    private BigDecimal bolsaArrastrada = BigDecimal.ZERO;

    @Column(name = "creada_at", insertable = false, updatable = false)
    private Instant creadaAt;

    protected Jornada() {
    }

    public Jornada(Integer idTemporada, Integer numero, String nombre, BigDecimal precio, BigDecimal pctPremio) {
        this.idTemporada = idTemporada;
        this.numero = numero;
        this.nombre = nombre;
        this.precio = precio;
        this.pctPremio = pctPremio;
    }

    public Integer getId() { return id; }
    public Integer getIdTemporada() { return idTemporada; }
    public Integer getNumero() { return numero; }
    public String getNombre() { return nombre; }
    public EstadoJornada getEstado() { return estado; }
    public boolean isVisible() { return visible; }
    public BigDecimal getPrecio() { return precio; }
    public BigDecimal getPctPremio() { return pctPremio; }
    public BigDecimal getBolsaArrastrada() { return bolsaArrastrada; }
    public Instant getCreadaAt() { return creadaAt; }

    public void setNombre(String nombre) { this.nombre = nombre; }
    public void setEstado(EstadoJornada estado) { this.estado = estado; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public void setPrecio(BigDecimal precio) { this.precio = precio; }
    public void setPctPremio(BigDecimal pctPremio) { this.pctPremio = pctPremio; }
}
