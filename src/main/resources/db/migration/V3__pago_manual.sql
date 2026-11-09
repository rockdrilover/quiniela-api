-- =====================================================================
-- Pagos manuales: el admin puede registrar que un usuario le pagó
-- (efectivo, transferencia sin comprobante...) sin que suba archivo.
-- La tabla comprobantes pasa a ser el registro de pagos de la quiniela:
--   tipo = 'comprobante' -> archivo subido por el usuario (flujo de revisión)
--   tipo = 'manual'      -> lo registra el admin, nace aprobado y sin archivo
-- =====================================================================

ALTER TABLE comprobantes
    ADD COLUMN tipo VARCHAR(20) NOT NULL DEFAULT 'comprobante'
        CHECK (tipo IN ('comprobante', 'manual')),
    ADD COLUMN nota VARCHAR(255);

ALTER TABLE comprobantes
    ALTER COLUMN archivo_key  DROP NOT NULL,
    ALTER COLUMN mime_type    DROP NOT NULL,
    ALTER COLUMN tamano_bytes DROP NOT NULL;

-- La regla "validar solo tras ver el archivo" aplica solo a comprobantes con archivo
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'comprobantes'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%visto_at%'
    LOOP
        EXECUTE format('ALTER TABLE comprobantes DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE comprobantes
    ADD CONSTRAINT ck_comprobante_visto
        CHECK (tipo = 'manual' OR estado <> 'aprobado' OR visto_at IS NOT NULL),
    ADD CONSTRAINT ck_comprobante_archivo
        CHECK (
            (tipo = 'comprobante' AND archivo_key IS NOT NULL AND mime_type IS NOT NULL AND tamano_bytes IS NOT NULL)
         OR (tipo = 'manual' AND archivo_key IS NULL AND estado = 'aprobado' AND revisado_por IS NOT NULL)
        );
