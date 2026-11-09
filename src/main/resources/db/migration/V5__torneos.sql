-- =====================================================================
-- Torneos (tabla temporadas): el admin los crea y administra.
--   proximo    -> se pueden preparar sus jornadas, pero no abrirlas
--   en_curso   -> el torneo que ve la gente; solo uno a la vez
--   finalizado -> cerrado: ya no acepta jornadas nuevas
-- El número de jornadas es libre.
-- =====================================================================

ALTER TABLE temporadas
    ADD COLUMN estado VARCHAR(20) NOT NULL DEFAULT 'proximo'
        CHECK (estado IN ('proximo', 'en_curso', 'finalizado')),
    ADD COLUMN fecha_inicio DATE,
    ADD COLUMN fecha_fin    DATE,
    ADD COLUMN creada_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_temporada_fechas CHECK (fecha_fin IS NULL OR fecha_inicio IS NULL OR fecha_fin >= fecha_inicio);

-- La activa más reciente queda en curso; las demás activas, próximas; las inactivas, finalizadas
UPDATE temporadas SET estado = 'finalizado' WHERE NOT activa;
UPDATE temporadas SET estado = 'en_curso'
WHERE id_temporada = (SELECT MAX(id_temporada) FROM temporadas WHERE activa);

ALTER TABLE temporadas DROP COLUMN activa;

CREATE UNIQUE INDEX uq_temporada_en_curso ON temporadas ((estado)) WHERE estado = 'en_curso';
