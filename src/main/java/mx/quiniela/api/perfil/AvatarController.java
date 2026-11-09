package mx.quiniela.api.perfil;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import mx.quiniela.api.perfil.AvatarService.Imagen;
import mx.quiniela.api.perfil.PerfilDtos.PerfilDto;

@RestController
public class AvatarController {

    private final AvatarService avatares;
    private final PerfilService perfil;

    public AvatarController(AvatarService avatares, PerfilService perfil) {
        this.avatares = avatares;
        this.perfil = perfil;
    }

    public record PresetRequest(@NotBlank(message = "Elige un avatar") String preset) {
    }

    /** multipart/form-data con el campo "archivo" (JPG, PNG o WEBP, máx. 2 MB). */
    @PostMapping(path = "/api/perfil/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PerfilDto subir(@AuthenticationPrincipal Jwt jwt,
                           @RequestPart(name = "archivo", required = false) MultipartFile archivo) {
        avatares.subir(usuario(jwt), archivo);
        return perfil.perfil(usuario(jwt));
    }

    @PutMapping("/api/perfil/avatar")
    public PerfilDto preset(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PresetRequest req) {
        avatares.elegirPreset(usuario(jwt), req.preset());
        return perfil.perfil(usuario(jwt));
    }

    @DeleteMapping("/api/perfil/avatar")
    public PerfilDto quitar(@AuthenticationPrincipal Jwt jwt) {
        avatares.quitar(usuario(jwt));
        return perfil.perfil(usuario(jwt));
    }

    /** Público: las fotos tienen nombre aleatorio y no cambian (una foto nueva es otro archivo). */
    @GetMapping("/api/avatares/{anio}/{mes}/{archivo}")
    public ResponseEntity<byte[]> ver(@PathVariable String anio, @PathVariable String mes, @PathVariable String archivo) {
        Imagen img = avatares.leer(AvatarService.CARPETA + "/" + anio + "/" + mes + "/" + archivo);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.mimeType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(img.datos());
    }

    private static Integer usuario(Jwt jwt) {
        return Integer.valueOf(jwt.getSubject());
    }
}
