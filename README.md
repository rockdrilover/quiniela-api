# Quiniela Liga MX · API

Spring Boot 4.1 · Java 21 · PostgreSQL · Flyway

## Correr en local

1. Una sola vez: `psql -U postgres -f db\crear-bd-local.sql` (crea las bases `quiniela` y `quiniela_test`).
2. Copia `.env.example` a `.env` y llena los valores (el `.env` no se sube a git).
3. `.\mvnw.cmd spring-boot:run` → http://localhost:8080
4. Pruebas: `.\mvnw.cmd test` (usan la base `quiniela_test`).

Sin `MAIL_HOST` los correos no se envían: se escriben en la consola (útil para ver el código de recuperación).

## Autenticación

Todas las rutas protegidas usan `Authorization: Bearer <accessToken>`.
El access token dura 15 min; cuando expire, el front llama a `/refresh` con el refresh token (dura 30 días y **cambia en cada uso**: guarda siempre el nuevo).

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| POST | `/api/auth/login` | `{usuario, password}` (usuario = correo o username) | 200 `TokensResponse` · 401 `CREDENCIALES_INVALIDAS` · 403 `CUENTA_BLOQUEADA` |
| POST | `/api/auth/registro` | `{username, nombre, correo, password, confirmacion}` | 201 `TokensResponse` (estado `pendiente`) · 400 · 409 `USERNAME_EN_USO` / `CORREO_EN_USO` |
| POST | `/api/auth/refresh` | `{refreshToken}` | 200 `TokensResponse` · 401 `SESION_INVALIDA` |
| POST | `/api/auth/logout` | `{refreshToken}` | 204 |
| GET | `/api/auth/yo` | — (con token) | 200 `UsuarioDto` |
| PUT | `/api/auth/password` | `{actual, nueva, confirmacion}` (con token) | 200 `TokensResponse` (cierra las demás sesiones) · 400 |
| POST | `/api/auth/recuperar` | `{correo}` | 202 siempre (exista o no el correo) |
| POST | `/api/auth/recuperar/verificar` | `{correo, codigo}` | 204 · 400 `CODIGO_INVALIDO` |
| POST | `/api/auth/recuperar/restablecer` | `{correo, codigo, nueva, confirmacion}` | 204 · 400 |

`TokensResponse = {accessToken, refreshToken, expiraEn, usuario: {id, username, nombre, correo, rol, estado}}`

Errores: `{status, detail, codigo, campos?}` — `detail` es el mensaje para mostrar al usuario.

**Límite de intentos de login:** 5 fallos seguidos bloquean la cuenta 15 min (ni con la contraseña correcta entra; recuperar la contraseña por correo quita el bloqueo). 20 fallos desde una misma IP, en cualquier cuenta, frenan esa IP 15 min. En ambos casos responde `429 DEMASIADOS_INTENTOS` con `reintentarEn` (segundos) y el header `Retry-After`. Desbloqueo manual: `UPDATE usuarios SET intentos_fallidos=0, bloqueo_login_hasta=NULL WHERE username='...'`.

**Contraseñas:** 8 a 72 caracteres, al menos una letra y un número.
**Código de recuperación:** 6 dígitos, vence en 15 min, 5 intentos, solo el último enviado es válido, máximo 3 envíos cada 15 min.

**Registro:** `username` de 3 a 30 caracteres (letras, números, `.` o `_`, sin espacios); es el nombre que sale en el ranking. Username y correo son únicos sin importar mayúsculas. El usuario nace `pendiente`: puede entrar, pero no crear quinielas hasta que el admin lo apruebe. Se le manda un correo de bienvenida y a los admins un aviso de "nuevo registro pendiente".

## Administración de usuarios (solo rol admin)

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/admin/usuarios?estado=pendiente&pagina=0&tamano=20` | `{elementos[], pagina, tamano, total, totalPaginas}` (estado opcional: `pendiente`, `aprobado`, `bloqueado`) |
| PUT | `/api/admin/usuarios/{id}/aprobar` | 200 (manda correo "tu cuenta fue aprobada") · 409 si no está pendiente |
| PUT | `/api/admin/usuarios/{id}/bloquear` | 200 (cierra todas sus sesiones) · 400 si es admin o es uno mismo |
| PUT | `/api/admin/usuarios/{id}/desbloquear` | 200 (vuelve a `aprobado`) |

| GET | `/api/admin/usuarios?q=texto` | Busca en username, nombre y correo (se combina con `estado`) |
| GET | `/api/admin/usuarios/conteos` | `{pendiente, aprobado, bloqueado}` |
| GET | `/api/admin/usuarios/{id}` | Perfil completo (como `/api/perfil`) |
| PUT | `/api/admin/usuarios/{id}` | `{nombre, correo}` · 409 `CORREO_EN_USO` (el username no se cambia) |
| DELETE | `/api/admin/usuarios/{id}` | Borrado lógico: ya no entra ni sale en la lista; sus quinielas y ranking se conservan. No aplica a uno mismo ni a otros admins |

Cada acción queda en `bitacora_admin`. Un usuario no admin recibe 403.

## Perfil (pantalla 2)

| Método | Ruta | Notas |
|---|---|---|
| GET | `/api/perfil` | `{usuario, estadisticas: {quinielasJugadas, puntosTotales, exactosTotales, quinielasGanadas, premiosGanados}, historial[]}` (solo quinielas con pago validado) |
| PUT | `/api/perfil` | `{nombre}` — el usuario solo cambia su nombre; correo lo cambia el admin |
| POST | `/api/perfil/avatar` | multipart, campo `archivo` (JPG/PNG/WEBP, máx. 2 MB) → perfil |
| PUT | `/api/perfil/avatar` | `{preset}`: `balon`, `trofeo`, `estrella`, `rayo`, `fuego`, `escudo`, `cohete`, `medalla` |
| DELETE | `/api/perfil/avatar` | Vuelve a las iniciales |
| GET | `/api/avatares/{yyyy}/{MM}/{archivo}` | **Público** (para `<img>`); nombre aleatorio, caché larga |

`usuario.avatar` (en la sesión, el perfil, el ranking, pagos y usuarios admin) es `"/api/avatares/..."`, `"preset:<nombre>"` o `null` (el front muestra las iniciales). Al cambiar de foto, la anterior se borra del disco.

El cambio de contraseña es `PUT /api/auth/password` (arriba).

Tras la aprobación, el token del usuario sigue diciendo `pendiente` hasta que se renueve (máx. 15 min) o vuelva a entrar.

## Jornadas y partidos (solo rol admin)

Ciclo: `borrador` (se arman partidos) → `abierta` (acepta pronósticos hasta que empieza el primer partido; solo una a la vez) → `cerrada` (se capturan marcadores) → `finalizada` (ranking definitivo). `estadoVisual` combina estado y hora: `BORRADOR`, `ACTIVA`, `CERRADA`, `EN_JUEGO`, `FINALIZADA`.

| Método | Ruta | Notas |
|---|---|---|
| GET | `/api/equipos` | Catálogo (cualquier usuario con sesión) |
| GET | `/api/admin/jornadas` | Jornadas de la temporada activa, la más nueva primero |
| POST | `/api/admin/jornadas` | `{nombre?, precio, pctPremio?}` — número consecutivo automático |
| GET/PUT/DELETE | `/api/admin/jornadas/{id}` | Detalle con partidos · editar (precio solo en borrador) · eliminar (borrador sin quinielas) |
| POST | `/api/admin/jornadas/{id}/abrir` · `/cerrar` · `/finalizar` | Abrir exige partidos y que el primero no haya empezado; finalizar exige que no queden partidos pendientes |
| PUT | `/api/admin/jornadas/{id}/visible` | `{visible}` — ocultar/mostrar (no aplica a una abierta) |
| POST | `/api/admin/jornadas/{id}/partidos` | `{idLocal, idVisitante, fechaHora}` (solo borrador; un equipo, un partido por jornada) |
| PUT/DELETE | `/api/admin/partidos/{id}` | Editar o quitar (solo borrador) |
| PUT | `/api/admin/partidos/{id}/resultado` | `{golesLocal, golesVisitante}` — finaliza o corrige; solo cuando la jornada ya no acepta pronósticos |
| POST | `/api/admin/partidos/{id}/suspender` | No cuenta para puntos ni para el cierre |

Todas responden la jornada actualizada `{jornada, partidos[]}`. Los puntos y el ranking los calculan las vistas de la BD al instante. Cada cambio de estado y marcador queda en `bitacora_admin`.

## Quinielas y pronósticos (cualquier usuario con sesión)

Ciclo: `borrador` (puede ir incompleta) → **enviar** con todos los partidos → `pendiente_pago` → (pago) `en_revision` → `participando` / `rechazada`. Solo usuarios **aprobados** crean o editan. Los marcadores se pueden cambiar mientras la jornada acepte pronósticos (abierta y antes del primer partido); después todo queda en solo lectura (lo vigila también el trigger de la BD). Máximo 10 quinielas por usuario por jornada. Goles de 0 a 20; L/E/V lo calcula la BD a partir del marcador (lógica espejo).

| Método | Ruta | Notas |
|---|---|---|
| GET | `/api/jornadas/actual` | La jornada abierta (o la visible más reciente) con sus partidos · 204 si no hay |
| GET | `/api/quinielas` | `{actual, puedeCrear, motivoNoPuedeCrear, jornadas[], quinielas[]}` — pantalla 3 |
| GET | `/api/quinielas/{id}` | `{quiniela, jornada, pronosticos[]}` con puntos por partido (null si aún no hay marcador). 404 si es ajena (el admin puede ver todas) |
| POST | `/api/quinielas` | `{idJornada, enviar, pronosticos:[{idPartido, golesLocal, golesVisitante}]}` → 201 |
| PUT | `/api/quinielas/{id}` | `{enviar, pronosticos[]}` — reemplaza los pronósticos; una enviada debe quedar completa |
| DELETE | `/api/quinielas/{id}` | Solo `borrador`/`pendiente_pago`, sin comprobante y con la jornada abierta |

Errores: 400 `FALTAN_PRONOSTICOS` / `PARTIDO_INVALIDO` / `PARTIDO_REPETIDO` · 403 `USUARIO_NO_APROBADO` · 409 `JORNADA_CERRADA` / `LIMITE_QUINIELAS`. Los partidos suspendidos no se pronostican.

## Pagos (comprobantes)

El usuario sube la foto o PDF de su pago **antes del cierre de la jornada** (así nadie paga solo cuando ya va ganando). La quiniela pasa a `en_revision`. El admin abre el comprobante (eso lo marca como visto) y solo entonces puede **validar** (→ `participando`, entra al ranking) o **rechazar** con motivo (→ `rechazada`; el usuario puede subir otro mientras la jornada siga abierta). Al usuario le llega un correo en ambos casos.

Archivos: JPG, PNG, WEBP o PDF, máx. 10 MB. El tipo se detecta por el contenido (no por la extensión). Se guardan en `ARCHIVOS_DIR` (por defecto `./datos`, ignorado por git), nunca en una carpeta pública: solo se descargan por la API con sesión. **En producción esa carpeta debe ser un volumen persistente** (o cambiar `AlmacenArchivos` a S3).

| Método | Ruta | Notas |
|---|---|---|
| POST | `/api/quinielas/{id}/comprobante` | multipart, campo `archivo` → quiniela actualizada · 400 `TIPO_INVALIDO` / `SIN_ARCHIVO` · 409 `ESTADO_INVALIDO` / `JORNADA_CERRADA` · 413 `ARCHIVO_GRANDE` |
| GET | `/api/quinielas/{id}/comprobante` | El dueño (o un admin) ve su último comprobante |
| GET | `/api/admin/pagos?estado=en_revision` | `{conteos, elementos[]}` — también `aprobado`, `rechazado`; opcional `idJornada` |
| GET | `/api/admin/pagos/{id}/archivo` | Visor; lo marca como visto |
| PUT | `/api/admin/pagos/{id}/aprobar` | 409 `SIN_VER` si no se ha abierto · 409 `YA_REVISADO` |
| PUT | `/api/admin/pagos/{id}/rechazar` | `{motivo}` (3 a 255 caracteres) |
| GET | `/api/admin/pagos/por-cobrar` | Quinielas enviadas sin pago (`pendiente_pago`/`rechazada`) de jornadas no finalizadas; opcional `idJornada` |
| POST | `/api/admin/quinielas/{id}/pago-manual` | `{nota?}` — pago en efectivo o por fuera: crea un pago `tipo: manual` ya aprobado y la quiniela pasa a `participando` · 409 `ESTADO_INVALIDO` (borrador, ya pagada o con comprobante en revisión) / `JORNADA_FINALIZADA` |

**Pago manual:** el comprobante es opcional. Si el jugador le paga al admin en persona, el admin lo registra desde "Por cobrar" (se permite aun después del cierre, hasta finalizar la jornada). Queda en la bitácora como `PAGO_MANUAL` y el jugador recibe el correo de pago validado. `conteos` incluye `por_cobrar`.

`GET /api/quinielas/{id}` incluye `pago: {puedeSubir, comprobante: {tipo, estado, subidoAt, mimeType, motivoRechazo}}` (`tipo`: `comprobante` | `manual`). Validar y rechazar quedan en `bitacora_admin` (`VALIDAR_PAGO`, `RECHAZAR_PAGO`).

## Ranking (cualquier usuario con sesión)

Solo cuentan las quinielas `participando` (pago validado). Puntos: 3 marcador exacto, 1 resultado (L/E/V), 0 fallo; desempate por número de exactos y, si siguen iguales, comparten lugar. Al finalizar la jornada los de la posición 1 se reparten la bolsa (`v_ganadores_jornada`). **Los pronósticos de los demás no se envían mientras la jornada acepte pronósticos** (para que nadie copie); los propios siempre.

| Método | Ruta | Notas |
|---|---|---|
| GET | `/api/ranking/jornadas` | `{actual, jornadas[]}` para el selector |
| GET | `/api/ranking?idJornada=` | `{jornada, partidos[], bolsa, pronosticosVisibles, filas[]}` · sin `idJornada` usa la vigente · 204 si no hay ninguna |

## Correo en producción (cualquier SMTP)

| Proveedor | MAIL_HOST | Notas |
|---|---|---|
| Amazon SES | `email-smtp.<region>.amazonaws.com` | Muy barato; hay que verificar el dominio/remitente y salir del "sandbox" |
| Gmail | `smtp.gmail.com` | Requiere verificación en 2 pasos y una "contraseña de aplicación"; límite ~500/día |
