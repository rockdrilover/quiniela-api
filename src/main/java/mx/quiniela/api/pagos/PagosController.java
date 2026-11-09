package mx.quiniela.api.pagos;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import mx.quiniela.api.pagos.PagoDtos.ComprobanteAdminDto;
import mx.quiniela.api.pagos.PagoDtos.ListaPagosDto;
import mx.quiniela.api.pagos.PagoDtos.PagoManualRequest;
import mx.quiniela.api.pagos.PagoDtos.PorCobrarDto;
import mx.quiniela.api.pagos.PagoDtos.RechazoRequest;
import mx.quiniela.api.pagos.PagosService.Archivo;
import mx.quiniela.api.quinielas.QuinielaDtos.QuinielaDetalleDto;
import mx.quiniela.api.quinielas.QuinielasService;

@RestController
@RequestMapping("/api")
public class PagosController {

    private final PagosService pagos;
    private final QuinielasService quinielas;

    public PagosController(PagosService pagos, QuinielasService quinielas) {
        this.pagos = pagos;
        this.quinielas = quinielas;
    }

    // ---------------------------------------------------------------- usuario

    /** multipart/form-data con el campo "archivo". Responde la quiniela actualizada (en revisión). */
    @PostMapping(path = "/quinielas/{id}/comprobante", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuinielaDetalleDto subir(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id,
                                    @RequestPart(name = "archivo", required = false) MultipartFile archivo) {
        pagos.subir(usuario(jwt), id, archivo);
        return quinielas.detalle(usuario(jwt), false, id);
    }

    @GetMapping("/quinielas/{id}/comprobante")
    public ResponseEntity<byte[]> verPropio(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return responder(pagos.archivoPropio(usuario(jwt), esAdmin(jwt), id));
    }

    // ---------------------------------------------------------------- admin

    @GetMapping("/admin/pagos")
    @PreAuthorize("hasRole('admin')")
    public ListaPagosDto listar(@RequestParam(defaultValue = "en_revision") EstadoComprobante estado,
                                @RequestParam(required = false) Integer idJornada) {
        return pagos.listar(estado, idJornada);
    }

    /** Quinielas enviadas que esperan pago: el admin puede registrar un pago recibido por fuera. */
    @GetMapping("/admin/pagos/por-cobrar")
    @PreAuthorize("hasRole('admin')")
    public List<PorCobrarDto> porCobrar(@RequestParam(required = false) Integer idJornada) {
        return pagos.porCobrar(idJornada);
    }

    /** Pago en efectivo / por fuera: la quiniela pasa a participando sin comprobante. */
    @PostMapping("/admin/quinielas/{id}/pago-manual")
    @PreAuthorize("hasRole('admin')")
    public ComprobanteAdminDto pagoManual(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id,
                                          @Valid @RequestBody(required = false) PagoManualRequest req) {
        return pagos.registrarManual(usuario(jwt), id, req == null ? null : req.nota());
    }

    /** Visor de comprobantes: abrirlo lo marca como visto. */
    @GetMapping("/admin/pagos/{id}/archivo")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<byte[]> verAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return responder(pagos.archivoAdmin(usuario(jwt), id));
    }

    @PutMapping("/admin/pagos/{id}/aprobar")
    @PreAuthorize("hasRole('admin')")
    public ComprobanteAdminDto aprobar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id) {
        return pagos.aprobar(usuario(jwt), id);
    }

    @PutMapping("/admin/pagos/{id}/rechazar")
    @PreAuthorize("hasRole('admin')")
    public ComprobanteAdminDto rechazar(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer id,
                                        @Valid @RequestBody RechazoRequest req) {
        return pagos.rechazar(usuario(jwt), id, req.motivo());
    }

    // ---------------------------------------------------------------- auxiliares

    private static ResponseEntity<byte[]> responder(Archivo a) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(a.nombre()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(a.datos());
    }

    private static boolean esAdmin(Jwt jwt) {
        return "admin".equals(jwt.getClaimAsString("rol"));
    }

    private static Integer usuario(Jwt jwt) {
        return Integer.valueOf(jwt.getSubject());
    }
}
