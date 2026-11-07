package mx.quiniela.api.pagos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guarda los comprobantes en disco, fuera de cualquier carpeta pública: solo se leen a través de la API.
 * La clave es relativa ("comprobantes/2026/11/uuid.jpg"), así se puede cambiar a S3 sin tocar la BD.
 */
@Component
public class AlmacenArchivos {

    private final Path raiz;

    public AlmacenArchivos(@Value("${app.archivos.dir:./datos}") String dir) {
        this.raiz = Path.of(dir).toAbsolutePath().normalize();
    }

    public String guardar(byte[] datos, String extension) {
        LocalDate hoy = LocalDate.now();
        String clave = "comprobantes/%d/%02d/%s.%s".formatted(hoy.getYear(), hoy.getMonthValue(), UUID.randomUUID(), extension);
        Path destino = resolver(clave);
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, datos);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el archivo", e);
        }
        return clave;
    }

    public byte[] leer(String clave) {
        try {
            return Files.readAllBytes(resolver(clave));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo", e);
        }
    }

    public void borrar(String clave) {
        try {
            Files.deleteIfExists(resolver(clave));
        } catch (IOException e) {
            // no es crítico: queda un archivo huérfano
        }
    }

    private Path resolver(String clave) {
        Path p = raiz.resolve(clave).normalize();
        if (!p.startsWith(raiz)) throw new IllegalArgumentException("Clave de archivo inválida");
        return p;
    }
}
