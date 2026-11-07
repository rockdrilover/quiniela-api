package mx.quiniela.api.catalogos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "temporadas")
public class Temporada {

    @Id
    @Column(name = "id_temporada")
    private Integer id;

    @Column(nullable = false, length = 50)
    private String nombre;

    @Column(nullable = false)
    private boolean activa;

    protected Temporada() {
    }

    public Integer getId() { return id; }
    public String getNombre() { return nombre; }
    public boolean isActiva() { return activa; }
}
