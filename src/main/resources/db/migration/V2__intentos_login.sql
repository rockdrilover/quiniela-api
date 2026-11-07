-- Protección contra adivinar contraseñas:
-- después de N intentos fallidos seguidos la cuenta se bloquea temporalmente para el login.
ALTER TABLE usuarios
    ADD COLUMN intentos_fallidos   SMALLINT    NOT NULL DEFAULT 0 CHECK (intentos_fallidos >= 0),
    ADD COLUMN bloqueo_login_hasta TIMESTAMPTZ;
