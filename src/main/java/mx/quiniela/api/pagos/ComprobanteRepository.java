package mx.quiniela.api.pagos;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComprobanteRepository extends JpaRepository<Comprobante, Integer> {

    Optional<Comprobante> findFirstByIdQuinielaOrderByIdDesc(Integer idQuiniela);
}
