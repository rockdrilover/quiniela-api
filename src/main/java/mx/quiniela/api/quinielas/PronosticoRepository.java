package mx.quiniela.api.quinielas;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PronosticoRepository extends JpaRepository<Pronostico, Long> {

    List<Pronostico> findByIdQuiniela(Integer idQuiniela);

    List<Pronostico> findByIdQuinielaIn(Collection<Integer> idsQuiniela);

    void deleteByIdQuiniela(Integer idQuiniela);
}
