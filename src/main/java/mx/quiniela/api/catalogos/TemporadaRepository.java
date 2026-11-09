package mx.quiniela.api.catalogos;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TemporadaRepository extends JpaRepository<Temporada, Integer> {

    Optional<Temporada> findFirstByEstadoOrderByIdDesc(EstadoTorneo estado);

    /** El torneo en curso (solo puede haber uno). */
    default Optional<Temporada> enCurso() {
        return findFirstByEstadoOrderByIdDesc(EstadoTorneo.en_curso);
    }

    List<Temporada> findAllByOrderByIdDesc();

    boolean existsByNombreIgnoreCase(String nombre);

    boolean existsByNombreIgnoreCaseAndIdNot(String nombre, Integer id);
}
