package mx.quiniela.api.quinielas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Marcador previsto para un partido. La selección L/E/V la deriva la BD (columna generada "seleccion"),
 * así nunca puede contradecir a los goles ("lógica espejo").
 * El trigger pronosticos_validos impide guardar después del cierre.
 */
@Entity
@Table(name = "pronosticos")
public class Pronostico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_pronostico")
    private Long id;

    @Column(name = "id_quiniela", nullable = false, updatable = false)
    private Integer idQuiniela;

    @Column(name = "id_partido", nullable = false, updatable = false)
    private Integer idPartido;

    @Column(name = "goles_local", nullable = false)
    private short golesLocal;

    @Column(name = "goles_visitante", nullable = false)
    private short golesVisitante;

    protected Pronostico() {
    }

    public Pronostico(Integer idQuiniela, Integer idPartido, short golesLocal, short golesVisitante) {
        this.idQuiniela = idQuiniela;
        this.idPartido = idPartido;
        this.golesLocal = golesLocal;
        this.golesVisitante = golesVisitante;
    }

    /** @return true si cambió el marcador. */
    public boolean marcar(short local, short visitante) {
        if (golesLocal == local && golesVisitante == visitante) return false;
        golesLocal = local;
        golesVisitante = visitante;
        return true;
    }

    /** Igual que fn_seleccion de la BD. */
    public String seleccion() {
        int c = Short.compare(golesLocal, golesVisitante);
        return c > 0 ? "L" : c < 0 ? "V" : "E";
    }

    public Long getId() { return id; }
    public Integer getIdQuiniela() { return idQuiniela; }
    public Integer getIdPartido() { return idPartido; }
    public short getGolesLocal() { return golesLocal; }
    public short getGolesVisitante() { return golesVisitante; }
}
