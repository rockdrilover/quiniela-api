package mx.quiniela.api.catalogos;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipoRepository extends JpaRepository<Equipo, Integer> {

    List<Equipo> findByActivoTrueOrderByNombre();
}
