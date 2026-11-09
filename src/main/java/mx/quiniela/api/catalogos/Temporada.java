package mx.quiniela.api.catalogos;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un torneo (Apertura 2026, Clausura 2027...). Sus jornadas cuelgan de él; el número de jornadas es libre. */
@Entity
@Table(name = "temporadas")
public class Temporada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_temporada")
    private Integer id;

    @Column(nullable = false, length = 50)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoTorneo estado = EstadoTorneo.proximo;

    @Column(name = "fecha_inicio")
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin")
    private LocalDate fechaFin;

    @Generated
    @Column(name = "creada_at", insertable = false, updatable = false)
    private Instant creadaAt;

    protected Temporada() {
    }

    public Temporada(String nombre, LocalDate fechaInicio, LocalDate fechaFin) {
        this.nombre = nombre;
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
    }

    public void editar(String nombre, LocalDate fechaInicio, LocalDate fechaFin) {
        this.nombre = nombre;
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
    }

    public void setEstado(EstadoTorneo estado) { this.estado = estado; }

    public Integer getId() { return id; }
    public String getNombre() { return nombre; }
    public EstadoTorneo getEstado() { return estado; }
    public boolean enCurso() { return estado == EstadoTorneo.en_curso; }
    public boolean finalizado() { return estado == EstadoTorneo.finalizado; }
    public LocalDate getFechaInicio() { return fechaInicio; }
    public LocalDate getFechaFin() { return fechaFin; }
    public Instant getCreadaAt() { return creadaAt; }
}
