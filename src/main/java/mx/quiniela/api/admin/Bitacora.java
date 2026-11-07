package mx.quiniela.api.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Registra en bitacora_admin quién hizo qué (FINALIZAR_PARTIDO, VALIDAR_PAGO, APROBAR_USUARIO...). */
@Component
public class Bitacora {

    private final JdbcTemplate jdbc;

    public Bitacora(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void registrar(Integer idAdmin, String accion, String entidad, Integer idEntidad,
                          Map<String, ?> antes, Map<String, ?> despues) {
        jdbc.update("""
                INSERT INTO bitacora_admin (id_admin, accion, entidad, id_entidad, datos_antes, datos_despues)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """, idAdmin, accion, entidad, idEntidad, json(antes), json(despues));
    }

    /** JSON plano de un nivel (texto y números); suficiente para la bitácora. */
    static String json(Map<String, ?> datos) {
        if (datos == null) return null;
        StringBuilder sb = new StringBuilder("{");
        new LinkedHashMap<>(datos).forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(escapar(k)).append("\":");
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(escapar(v.toString())).append('"');
        });
        return sb.append('}').toString();
    }

    private static String escapar(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append("\\u%04x".formatted((int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
