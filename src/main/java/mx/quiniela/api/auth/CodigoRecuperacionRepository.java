package mx.quiniela.api.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodigoRecuperacionRepository extends JpaRepository<CodigoRecuperacion, Long> {

    /** El código más reciente sin usar del usuario (solo ese es válido). */
    Optional<CodigoRecuperacion> findFirstByIdUsuarioAndUsadoAtIsNullOrderByIdDesc(Integer idUsuario);

    long countByIdUsuarioAndCreadoAtAfter(Integer idUsuario, Instant desde);

    /** Anula los códigos anteriores cuando se pide uno nuevo. */
    @Modifying
    @Query("UPDATE CodigoRecuperacion c SET c.usadoAt = :ahora WHERE c.idUsuario = :idUsuario AND c.usadoAt IS NULL")
    int anularPendientes(@Param("idUsuario") Integer idUsuario, @Param("ahora") Instant ahora);
}
