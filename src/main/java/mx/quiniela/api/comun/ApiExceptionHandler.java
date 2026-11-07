package mx.quiniela.api.comun;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Formato de error uniforme (RFC 9457):
 * { "status": 400, "title": "...", "detail": "mensaje para el usuario", "codigo": "...", "campos": {...} }
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> negocio(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("codigo", ex.getCodigo());
        ResponseEntity.BodyBuilder r = ResponseEntity.status(ex.getStatus());
        if (ex instanceof ApiException.DemasiadosIntentos d) {
            pd.setProperty("reintentarEn", d.getSegundos());
            r.header(HttpHeaders.RETRY_AFTER, String.valueOf(d.getSegundos()));
        }
        return r.body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException ex) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> campos.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Revisa los datos enviados");
        pd.setProperty("codigo", "DATOS_INVALIDOS");
        pd.setProperty("campos", campos);
        return pd;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail archivoGrande(MaxUploadSizeExceededException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "El archivo pesa más de 10 MB");
        pd.setProperty("codigo", "ARCHIVO_GRANDE");
        return pd;
    }

    @ExceptionHandler(MultipartException.class)
    ProblemDetail multipart(MultipartException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Selecciona la foto o el PDF de tu comprobante");
        pd.setProperty("codigo", "SIN_ARCHIVO");
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail jsonInvalido(HttpMessageNotReadableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "El cuerpo de la petición no es JSON válido");
        pd.setProperty("codigo", "JSON_INVALIDO");
        return pd;
    }
}
