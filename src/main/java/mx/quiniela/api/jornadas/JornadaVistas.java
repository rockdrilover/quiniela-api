package mx.quiniela.api.jornadas;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import mx.quiniela.api.catalogos.CatalogosController.EquipoDto;
import mx.quiniela.api.catalogos.Equipo;
import mx.quiniela.api.catalogos.EquipoRepository;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDetalleDto;
import mx.quiniela.api.jornadas.JornadaDtos.JornadaDto;
import mx.quiniela.api.jornadas.JornadaDtos.PartidoDto;

/** Arma los DTO de jornada y partidos; lo usan tanto el admin como los usuarios. */
@Component
public class JornadaVistas {

    private final JornadaRepository jornadas;
    private final PartidoRepository partidos;
    private final EquipoRepository equipos;
    private final Clock clock;

    public JornadaVistas(JornadaRepository jornadas, PartidoRepository partidos, EquipoRepository equipos, Clock clock) {
        this.jornadas = jornadas;
        this.partidos = partidos;
        this.equipos = equipos;
        this.clock = clock;
    }

    public List<Partido> partidosDe(Jornada j) {
        return partidos.findByIdJornadaOrderByFechaHoraAscOrdenAsc(j.getId());
    }

    public JornadaDetalleDto detalle(Jornada j) {
        List<Partido> ps = partidosDe(j);
        Map<Integer, Equipo> eq = equipos.findAll().stream().collect(Collectors.toMap(Equipo::getId, Function.identity()));
        List<PartidoDto> dtos = ps.stream().map(p -> new PartidoDto(
                p.getId(), p.getOrden(),
                EquipoDto.de(eq.get(p.getIdLocal())), EquipoDto.de(eq.get(p.getIdVisitante())),
                p.getFechaHora(), p.getGolesLocal(), p.getGolesVisitante(),
                JornadasAdminService.resultado(p), p.getEstado().name())).toList();
        return new JornadaDetalleDto(resumen(j, ps, clock.instant()), dtos);
    }

    public JornadaDto resumen(Jornada j, List<Partido> ps, Instant ahora) {
        Instant cierre = JornadasAdminService.fechaCierre(ps);
        int finalizados = (int) ps.stream().filter(p -> p.getEstado() == EstadoPartido.finalizado).count();
        return new JornadaDto(j.getId(), j.getNumero(), j.getNombre(), j.getEstado().name(),
                JornadasAdminService.estadoVisual(j, cierre, ahora), j.isVisible(), j.getPrecio(), j.getPctPremio(), cierre,
                ps.size(), finalizados, jornadas.contarQuinielas(j.getId()),
                JornadasAdminService.aceptaPronosticos(j, cierre, ahora));
    }

    public boolean aceptaPronosticos(Jornada j) {
        return JornadasAdminService.aceptaPronosticos(j, JornadasAdminService.fechaCierre(partidosDe(j)), clock.instant());
    }
}
