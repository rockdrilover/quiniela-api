package mx.quiniela.api.jornadas;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartidoRepository extends JpaRepository<Partido, Integer> {

    List<Partido> findByIdJornadaOrderByFechaHoraAscOrdenAsc(Integer idJornada);

    List<Partido> findByIdJornadaIn(Collection<Integer> idsJornada);

    @Query("SELECT COALESCE(MAX(p.orden), 0) FROM Partido p WHERE p.idJornada = :idJornada")
    int maxOrden(@Param("idJornada") Integer idJornada);

    void deleteByIdJornada(Integer idJornada);
}
