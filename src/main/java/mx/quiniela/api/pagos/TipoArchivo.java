package mx.quiniela.api.pagos;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Tipos aceptados para comprobantes. Se detectan por los primeros bytes del archivo (su firma),
 * no por la extensión ni por el Content-Type que manda el navegador, que se pueden falsificar.
 */
public enum TipoArchivo {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    PDF("application/pdf", "pdf");

    public final String mime;
    public final String extension;

    TipoArchivo(String mime, String extension) {
        this.mime = mime;
        this.extension = extension;
    }

    public static Optional<TipoArchivo> detectar(byte[] b) {
        if (empieza(b, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) return Optional.of(JPEG);
        if (empieza(b, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) return Optional.of(PNG);
        if (empieza(b, 0, ascii("RIFF")) && empieza(b, 8, ascii("WEBP"))) return Optional.of(WEBP);
        if (empieza(b, 0, ascii("%PDF-"))) return Optional.of(PDF);
        return Optional.empty();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean empieza(byte[] b, int desde, byte[] firma) {
        if (b.length < desde + firma.length) return false;
        for (int i = 0; i < firma.length; i++) {
            if (b[desde + i] != firma[i]) return false;
        }
        return true;
    }
}
