package mx.quiniela.api.correo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Envío de correos por SMTP (Amazon SES, Gmail, Brevo... cualquiera: solo cambian las variables MAIL_*).
 * Si no hay servidor SMTP configurado (desarrollo local), el correo se escribe en el log en lugar de enviarse.
 * El envío es asíncrono para no frenar la respuesta ni revelar por tiempo si el correo existe.
 */
@Service
public class CorreoService {

    private static final Logger log = LoggerFactory.getLogger(CorreoService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String remitente;

    private final boolean smtpConfigurado;

    public CorreoService(ObjectProvider<JavaMailSender> mailSender,
                         @Value("${app.correo.remitente:Quiniela Liga MX <no-responder@localhost>}") String remitente,
                         @Value("${spring.mail.host:}") String host) {
        this.mailSender = mailSender;
        this.remitente = remitente;
        this.smtpConfigurado = !host.isBlank();
    }

    @Async
    public void enviarCodigoRecuperacion(String para, String nombre, String codigo, long minutosVigencia) {
        String asunto = "Tu código para recuperar la contraseña: " + codigo;
        String cuerpo = """
                Hola %s,

                Recibimos una solicitud para restablecer la contraseña de tu cuenta en la Quiniela Liga MX.

                Tu código es: %s

                Vence en %d minutos. Si no fuiste tú, ignora este correo: tu contraseña no cambiará.
                """.formatted(nombre, codigo, minutosVigencia);
        enviar(para, asunto, cuerpo);
    }

    @Async
    public void enviarAvisoCambioPassword(String para, String nombre) {
        String cuerpo = """
                Hola %s,

                La contraseña de tu cuenta en la Quiniela Liga MX se acaba de cambiar y se cerraron
                las sesiones abiertas en otros dispositivos.

                Si no fuiste tú, recupera tu cuenta con "¿Olvidaste tu contraseña?" y avisa al administrador.
                """.formatted(nombre);
        enviar(para, "Tu contraseña fue cambiada", cuerpo);
    }

    @Async
    public void enviarBienvenida(String para, String nombre, String username) {
        String cuerpo = """
                Hola %s,

                Tu cuenta en la Quiniela Liga MX quedó registrada con el usuario "%s".

                Un administrador tiene que aprobarla antes de que puedas crear quinielas.
                Te avisaremos por este medio en cuanto esté lista.
                """.formatted(nombre, username);
        enviar(para, "Bienvenido a la Quiniela Liga MX", cuerpo);
    }

    @Async
    public void enviarAvisoNuevoRegistro(String para, String username, String nombre, String correoUsuario) {
        String cuerpo = """
                Se registró un nuevo usuario y está pendiente de aprobación:

                Usuario: %s
                Nombre:  %s
                Correo:  %s

                Apruébalo desde el panel de administración.
                """.formatted(username, nombre, correoUsuario);
        enviar(para, "Nuevo registro pendiente: " + username, cuerpo);
    }

    @Async
    public void enviarCuentaAprobada(String para, String nombre) {
        String cuerpo = """
                Hola %s,

                ¡Tu cuenta en la Quiniela Liga MX ya fue aprobada! Ya puedes crear tus quinielas.
                Si ya tenías la app abierta, cierra sesión y vuelve a entrar.
                """.formatted(nombre);
        enviar(para, "Tu cuenta fue aprobada", cuerpo);
    }

    @Async
    public void enviarPagoValidado(String para, String nombre, String jornada, int numero, Integer folio) {
        String cuerpo = """
                Hola %s,

                Validamos tu pago de la Quiniela %d (folio #%d) de la %s.
                Ya estás participando: tus puntos cuentan para el ranking. ¡Suerte!
                """.formatted(nombre, numero, folio, jornada);
        enviar(para, "Pago validado: Quiniela #" + folio, cuerpo);
    }

    @Async
    public void enviarPagoRechazado(String para, String nombre, String jornada, int numero, Integer folio, String motivo) {
        String cuerpo = """
                Hola %s,

                No pudimos validar el comprobante de tu Quiniela %d (folio #%d) de la %s.
                Motivo: %s

                Puedes subir otro comprobante desde la app mientras la jornada siga abierta.
                """.formatted(nombre, numero, folio, jornada, motivo);
        enviar(para, "Revisa tu pago: Quiniela #" + folio, cuerpo);
    }

    private void enviar(String para, String asunto, String cuerpo) {
        JavaMailSender sender = smtpConfigurado ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            log.warn("SMTP no configurado (modo desarrollo). Correo NO enviado.\nPara: {}\nAsunto: {}\n{}", para, asunto, cuerpo);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(remitente);
            msg.setTo(para);
            msg.setSubject(asunto);
            msg.setText(cuerpo);
            sender.send(msg);
        } catch (Exception e) {
            log.error("No se pudo enviar el correo a {}: {}", para, e.getMessage());
        }
    }
}
