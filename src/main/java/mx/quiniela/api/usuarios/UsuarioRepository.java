package mx.quiniela.api.usuarios;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, Integer> {

    /** Busca por correo o username sin importar mayúsculas (columnas CITEXT). Excluye borrados. */
    @Query(value = """
            SELECT * FROM usuarios
            WHERE (correo = CAST(:valor AS citext) OR username = CAST(:valor AS citext))
              AND eliminado_at IS NULL
            """, nativeQuery = true)
    Optional<Usuario> buscarPorCorreoOUsername(@Param("valor") String valor);

    @Query(value = """
            SELECT * FROM usuarios
            WHERE correo = CAST(:correo AS citext) AND eliminado_at IS NULL
            """, nativeQuery = true)
    Optional<Usuario> buscarPorCorreo(@Param("correo") String correo);

    boolean existsByRol(Rol rol);

    /** Incluye usuarios borrados: el username y el correo siguen ocupados por la restricción UNIQUE. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM usuarios WHERE username = CAST(:username AS citext))", nativeQuery = true)
    boolean usernameOcupado(@Param("username") String username);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM usuarios WHERE correo = CAST(:correo AS citext))", nativeQuery = true)
    boolean correoOcupado(@Param("correo") String correo);

    org.springframework.data.domain.Page<Usuario> findByEliminadoAtIsNull(
            org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Usuario> findByEstadoAndEliminadoAtIsNull(
            EstadoUsuario estado, org.springframework.data.domain.Pageable pageable);

    /** Correo en uso por otra cuenta (incluye borradas: el correo no se recicla). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM usuarios WHERE correo = CAST(:correo AS citext) AND id_usuario <> :excepto)",
           nativeQuery = true)
    boolean correoOcupadoPorOtro(@Param("correo") String correo, @Param("excepto") Integer excepto);

    /** Lista del admin con filtro opcional de estado y búsqueda por username, nombre o correo. */
    @Query(value = """
            SELECT * FROM usuarios
            WHERE eliminado_at IS NULL
              AND (CAST(:estado AS text) IS NULL OR estado::text = CAST(:estado AS text))
              AND (CAST(:q AS text) IS NULL OR username::text ILIKE '%' || CAST(:q AS text) || '%'
                   OR nombre ILIKE '%' || CAST(:q AS text) || '%' OR correo::text ILIKE '%' || CAST(:q AS text) || '%')
            """,
           countQuery = """
            SELECT COUNT(*) FROM usuarios
            WHERE eliminado_at IS NULL
              AND (CAST(:estado AS text) IS NULL OR estado::text = CAST(:estado AS text))
              AND (CAST(:q AS text) IS NULL OR username::text ILIKE '%' || CAST(:q AS text) || '%'
                   OR nombre ILIKE '%' || CAST(:q AS text) || '%' OR correo::text ILIKE '%' || CAST(:q AS text) || '%')
            """,
           nativeQuery = true)
    org.springframework.data.domain.Page<Usuario> buscarAdmin(@Param("estado") String estado, @Param("q") String q,
                                                              org.springframework.data.domain.Pageable pageable);

    @Query(value = "SELECT estado::text AS estado, COUNT(*) AS n FROM usuarios WHERE eliminado_at IS NULL GROUP BY estado",
           nativeQuery = true)
    java.util.List<Object[]> contarPorEstado();

    // SQL nativo: con literales de enum en JPQL, Hibernate castea al tipo "rol" (nombre de la clase Java), que no existe
    @Query(value = "SELECT * FROM usuarios WHERE rol = 'admin' AND estado = 'aprobado' AND eliminado_at IS NULL",
           nativeQuery = true)
    java.util.List<Usuario> adminsActivos();
}
