package mx.quiniela.api.jornadas;

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
@Table(name = "partidos")
public class Partido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_partido")
    private Integer id;

    @Column(name = "id_jornada", nullable = false)
    private Integer idJornada;

    @Column(nullable = false)
    private short orden;

    @Column(name = "id_equipo_local", nullable = false)
    private Integer idLocal;

    @Column(name = "id_equipo_visitante", nullable = false)
    private Integer idVisitante;

    @Column(name = "fecha_hora", nullable = false)
    private Instant fechaHora;

    @Column(name = "goles_local")
    private Short golesLocal;

    @Column(name = "goles_visitante")
    private Short golesVisitante;

    /** Columna generada por la BD a partir del marcador (L/E/V). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "seleccion", insertable = false, updatable = false)
    private Seleccion resultado;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "estado_partido", nullable = false)
    private EstadoPartido estado = EstadoPartido.programado;

    @Column(name = "finalizado_por")
    private Integer finalizadoPor;

    @Column(name = "finalizado_at")
    private Instant finalizadoAt;

    protected Partido() {
    }

    public Partido(Integer idJornada, short orden, Integer idLocal, Integer idVisitante, Instant fechaHora) {
        this.idJornada = idJornada;
        this.orden = orden;
        this.idLocal = idLocal;
        this.idVisitante = idVisitante;
        this.fechaHora = fechaHora;
    }

    public void programar(Integer idLocal, Integer idVisitante, Instant fechaHora) {
        this.idLocal = idLocal;
        this.idVisitante = idVisitante;
        this.fechaHora = fechaHora;
    }

    public void finalizar(short golesLocal, short golesVisitante, Integer idAdmin, Instant ahora) {
        this.golesLocal = golesLocal;
        this.golesVisitante = golesVisitante;
        this.estado = EstadoPartido.finalizado;
        this.finalizadoPor = idAdmin;
        this.finalizadoAt = ahora;
    }

    public void suspender() {
        this.estado = EstadoPartido.suspendido;
        this.golesLocal = null;
        this.golesVisitante = null;
        this.finalizadoPor = null;
        this.finalizadoAt = null;
    }

    public Integer getId() { return id; }
    public Integer getIdJornada() { return idJornada; }
    public short getOrden() { return orden; }
    public Integer getIdLocal() { return idLocal; }
    public Integer getIdVisitante() { return idVisitante; }
    public Instant getFechaHora() { return fechaHora; }
    public Short getGolesLocal() { return golesLocal; }
    public Short getGolesVisitante() { return golesVisitante; }
    public Seleccion getResultado() { return resultado; }
    public EstadoPartido getEstado() { return estado; }
    public Instant getFinalizadoAt() { return finalizadoAt; }
}
