package mx.quiniela.api.catalogos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "equipos")
public class Equipo {

    @Id
    @Column(name = "id_equipo")
    private Integer id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, length = 5)
    private String abreviatura;

    @Column(nullable = false, length = 50)
    private String liga;

    @Column(name = "url_logo", length = 500)
    private String urlLogo;

    @Column(nullable = false)
    private boolean activo;

    protected Equipo() {
    }

    public Integer getId() { return id; }
    public String getNombre() { return nombre; }
    public String getAbreviatura() { return abreviatura; }
    public String getLiga() { return liga; }
    public String getUrlLogo() { return urlLogo; }
    public boolean isActivo() { return activo; }
}
