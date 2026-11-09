-- =====================================================================
-- Nueva regla de puntos: atinarle al resultado (gana local, empate o
-- gana visitante) vale 1 punto, sea o no el marcador exacto. Fallar = 0.
-- El marcador exacto ya no da puntos extra, pero sigue contando como
-- desempate en el ranking (v_ranking_jornada: puntos, luego exactos).
--
-- Los puntos no se guardan: se calculan con esta función, así que la
-- regla aplica a todas las jornadas, incluidas las ya finalizadas.
-- =====================================================================

CREATE OR REPLACE FUNCTION fn_puntos(pl INT, pv INT, ml INT, mv INT)
RETURNS SMALLINT
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN ml IS NULL OR mv IS NULL                    THEN NULL
        WHEN fn_seleccion(pl, pv) = fn_seleccion(ml, mv) THEN 1
        ELSE 0
    END::SMALLINT
$$;
