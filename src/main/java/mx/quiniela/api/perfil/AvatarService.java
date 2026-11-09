package mx.quiniela.api.perfil;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import mx.quiniela.api.comun.ApiException;
import mx.quiniela.api.pagos.AlmacenArchivos;
import mx.quiniela.api.pagos.TipoArchivo;
import mx.quiniela.api.usuarios.Usuario;
import mx.quiniela.api.usuarios.UsuarioRepository;

/**
 * Avatar del usuario: uno predefinido ("preset:balon") o una foto propia (JPG, PNG o WEBP, máx. 2 MB).
 * Sin avatar, el front muestra las iniciales. Las fotos se sirven públicas en /api/avatares/... con un
 * nombre aleatorio: no son datos sensibles y así se pueden usar directo en una etiqueta img.
 */
@Service
public class AvatarService {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final Set<String> PRESETS =
            Set.of("balon", "trofeo", "estrella", "rayo", "fuego", "escudo", "cohete", "medalla");

    static final String CARPETA = "avatares";
    private static final String PREFIJO_URL = "/api/";
    /** avatares/yyyy/MM/uuid.ext */
    private static final Pattern CLAVE = Pattern.compile("avatares/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.(jpg|png|webp)");

    private final UsuarioRepository usuarios;
    private final AlmacenArchivos almacen;

    public AvatarService(UsuarioRepository usuarios, AlmacenArchivos almacen) {
        this.usuarios = usuarios;
        this.almacen = almacen;
    }

    public record Imagen(byte[] datos, String mimeType) {
    }

    @Transactional
    public void subir(Integer idUsuario, MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw ApiException.invalido("SIN_ARCHIVO", "Selecciona una imagen");
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw ApiException.invalido("ARCHIVO_GRANDE", "La imagen pesa más de 2 MB");
        }
        byte[] datos;
        try {
            datos = archivo.getBytes();
        } catch (IOException e) {
            throw ApiException.invalido("SIN_ARCHIVO", "No se pudo leer la imagen; intenta de nuevo");
        }
        TipoArchivo tipo = TipoArchivo.detectar(datos).filter(t -> t != TipoArchivo.PDF)
                .orElseThrow(() -> ApiException.invalido("TIPO_INVALIDO", "Sube una imagen JPG, PNG o WEBP"));

        String clave = almacen.guardar(CARPETA, datos, tipo.extension);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) almacen.borrar(clave);
            }
        });
        cambiar(idUsuario, PREFIJO_URL + clave);
    }

    @Transactional
    public void elegirPreset(Integer idUsuario, String preset) {
        if (preset == null || !PRESETS.contains(preset)) {
            throw ApiException.invalido("AVATAR_INVALIDO", "Ese avatar no existe");
        }
        cambiar(idUsuario, "preset:" + preset);
    }

    @Transactional
    public void quitar(Integer idUsuario) {
        cambiar(idUsuario, null);
    }

    /** Para el endpoint público: solo claves con el formato de avatar (nada de rutas arbitrarias). */
    public Imagen leer(String clave) {
        if (!CLAVE.matcher(clave).matches()) throw noEncontrado();
        String ext = clave.substring(clave.lastIndexOf('.') + 1);
        String mime = switch (ext) {
            case "jpg" -> TipoArchivo.JPEG.mime;
            case "png" -> TipoArchivo.PNG.mime;
            default -> TipoArchivo.WEBP.mime;
        };
        try {
            return new Imagen(almacen.leer(clave), mime);
        } catch (RuntimeException e) {
            throw noEncontrado();
        }
    }

    private void cambiar(Integer idUsuario, String nuevo) {
        Usuario u = usuarios.findById(idUsuario).filter(x -> x.getEliminadoAt() == null)
                .orElseThrow(AvatarService::noEncontrado);
        String anterior = u.getAvatarUrl();
        u.setAvatarUrl(nuevo);
        usuarios.flush();
        // La foto anterior se borra solo si el cambio se guardó
        if (anterior != null && anterior.startsWith(PREFIJO_URL + CARPETA + "/") && !anterior.equals(nuevo)) {
            String claveAnterior = anterior.substring(PREFIJO_URL.length());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    almacen.borrar(claveAnterior);
                }
            });
        }
    }

    private static ApiException noEncontrado() {
        return new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "No encontrado");
    }
}
