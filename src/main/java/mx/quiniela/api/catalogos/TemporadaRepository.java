package mx.quiniela.api.catalogos;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TemporadaRepository extends JpaRepository<Temporada, Integer> {

    /** La temporada activa más reciente. */
    Optional<Temporada> findFirstByActivaTrueOrderByIdDesc();
}
