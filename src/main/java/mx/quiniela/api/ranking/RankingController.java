package mx.quiniela.api.ranking;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mx.quiniela.api.ranking.RankingDtos.JornadasRankingDto;
import mx.quiniela.api.ranking.RankingDtos.RankingDto;

@RestController
@RequestMapping("/api/ranking")
public class RankingController {

    private final RankingService servicio;

    public RankingController(RankingService servicio) {
        this.servicio = servicio;
    }

    /** Jornadas publicadas para el selector, y cuál es la vigente. */
    @GetMapping("/jornadas")
    public JornadasRankingDto jornadas(@RequestParam(required = false) Integer idTorneo) {
        return servicio.jornadas(idTorneo);
    }

    /** Sin idJornada usa la vigente; 204 si todavía no hay ninguna publicada. */
    @GetMapping
    public ResponseEntity<RankingDto> ranking(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(required = false) Integer idJornada) {
        return servicio.ranking(Integer.valueOf(jwt.getSubject()), "admin".equals(jwt.getClaimAsString("rol")), idJornada)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
