package mx.quiniela.api.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SesionRepository extends JpaRepository<Sesion, Long> {

    Optional<Sesion> findByTokenHash(String tokenHash);

    /** Cierra todas las sesiones abiertas del usuario (cambio de contraseña, robo de token, etc.). */
    @Modifying
    @Query("UPDATE Sesion s SET s.revocadaAt = :ahora WHERE s.idUsuario = :idUsuario AND s.revocadaAt IS NULL")
    int revocarTodas(@Param("idUsuario") Integer idUsuario, @Param("ahora") Instant ahora);
}
