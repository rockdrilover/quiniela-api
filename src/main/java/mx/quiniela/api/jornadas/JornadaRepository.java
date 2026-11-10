package mx.quiniela.api.jornadas;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JornadaRepository extends JpaRepository<Jornada, Integer> {

    List<Jornada> findByIdTemporadaOrderByNumeroDesc(Integer idTemporada);

    List<Jornada> findByIdTemporadaAndVisibleTrueOrderByNumeroDesc(Integer idTemporada);

    @Query("SELECT COALESCE(MAX(j.numero), 0) FROM Jornada j WHERE j.idTemporada = :idTemporada")
    int maxNumero(@Param("idTemporada") Integer idTemporada);

    // SQL nativo: con literales de enum en JPQL, Hibernate castea a un tipo con el nombre de la clase Java
    @Query(value = "SELECT COUNT(*) FROM jornadas WHERE estado = 'abierta' AND id_jornada <> :excepto", nativeQuery = true)
    long contarOtrasAbiertas(@Param("excepto") Integer excepto);

    @Query(value = "SELECT COUNT(*) FROM quinielas WHERE id_jornada = :id", nativeQuery = true)
    long contarQuinielas(@Param("id") Integer idJornada);

    /** Quinielas que ya pagaron o mandaron comprobante: su monto ya no se puede cambiar. */
    @Query(value = "SELECT COUNT(*) FROM quinielas WHERE id_jornada = :id AND estado IN ('en_revision', 'participando')",
            nativeQuery = true)
    long contarQuinielasConPago(@Param("id") Integer idJornada);

    /** Al corregir el precio, las quinielas que aún no pagan toman el precio nuevo. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query(value = "UPDATE quinielas SET monto = :monto WHERE id_jornada = :id AND estado IN ('borrador', 'pendiente_pago', 'rechazada')",
            nativeQuery = true)
    int actualizarMontoSinPago(@Param("id") Integer idJornada, @Param("monto") java.math.BigDecimal monto);
}
