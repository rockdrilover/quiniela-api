package mx.quiniela.api.auth;

import mx.quiniela.api.comun.ApiException;

/** Reglas de contraseña: 8 a 72 caracteres, al menos una letra y un número. */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static void validar(String nueva, String confirmacion) {
        if (!nueva.equals(confirmacion)) {
            throw ApiException.invalido("PASSWORD_NO_COINCIDE", "La confirmación no coincide con la nueva contraseña");
        }
        if (nueva.length() < 8 || nueva.length() > 72) {
            throw ApiException.invalido("PASSWORD_DEBIL", "La contraseña debe tener entre 8 y 72 caracteres");
        }
        if (!nueva.matches(".*\\p{L}.*") || !nueva.matches(".*\\d.*")) {
            throw ApiException.invalido("PASSWORD_DEBIL", "La contraseña debe incluir al menos una letra y un número");
        }
    }
}
