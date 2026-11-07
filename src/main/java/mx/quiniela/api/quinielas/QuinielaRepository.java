package mx.quiniela.api.quinielas;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuinielaRepository extends JpaRepository<Quiniela, Integer> {

    List<Quiniela> findByIdUsuarioOrderByIdJornadaDescNumeroAsc(Integer idUsuario);

    long countByIdUsuarioAndIdJornada(Integer idUsuario, Integer idJornada);

    @Query("SELECT COALESCE(MAX(q.numero), 0) FROM Quiniela q WHERE q.idUsuario = :u AND q.idJornada = :j")
    int maxNumero(@Param("u") Integer idUsuario, @Param("j") Integer idJornada);

    @Query(value = "SELECT COUNT(*) FROM comprobantes WHERE id_quiniela = :id", nativeQuery = true)
    long contarComprobantes(@Param("id") Integer idQuiniela);
}
