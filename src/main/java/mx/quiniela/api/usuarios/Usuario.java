package mx.quiniela.api.usuarios;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_usuario")
    private Integer id;

    @Column(columnDefinition = "citext", nullable = false)
    private String username;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(columnDefinition = "citext", nullable = false)
    private String correo;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "rol_usuario", nullable = false)
    private Rol rol = Rol.usuario;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "estado_usuario", nullable = false)
    private EstadoUsuario estado = EstadoUsuario.pendiente;

    @Column(name = "aprobado_por")
    private Integer aprobadoPor;

    @Column(name = "aprobado_at")
    private Instant aprobadoAt;

    @Column(name = "fecha_registro", insertable = false, updatable = false)
    private Instant fechaRegistro;

    @Column(name = "ultimo_login")
    private Instant ultimoLogin;

    @Column(name = "eliminado_at")
    private Instant eliminadoAt;

    @Column(name = "intentos_fallidos", nullable = false)
    private short intentosFallidos;

    @Column(name = "bloqueo_login_hasta")
    private Instant bloqueoLoginHasta;

    protected Usuario() {
    }

    public Usuario(String username, String nombre, String correo, String passwordHash) {
        this.username = username;
        this.nombre = nombre;
        this.correo = correo;
        this.passwordHash = passwordHash;
    }

    /** Puede iniciar sesión: no bloqueado y no borrado. Un usuario "pendiente" sí entra, pero no puede crear quinielas. */
    public boolean puedeIniciarSesion() {
        return eliminadoAt == null && estado != EstadoUsuario.bloqueado;
    }

    /** true mientras dure el bloqueo temporal por intentos fallidos. */
    public boolean loginBloqueado(Instant ahora) {
        return bloqueoLoginHasta != null && bloqueoLoginHasta.isAfter(ahora);
    }

    /** Suma un intento fallido; al llegar al máximo bloquea el login hasta "ahora + duracion" y reinicia el contador. */
    public void registrarLoginFallido(int maxIntentos, java.time.Duration duracion, Instant ahora) {
        intentosFallidos++;
        if (intentosFallidos >= maxIntentos) {
            bloqueoLoginHasta = ahora.plus(duracion);
            intentosFallidos = 0;
        }
    }

    public void reiniciarIntentosLogin() {
        intentosFallidos = 0;
        bloqueoLoginHasta = null;
    }

    public void aprobar(Integer idAdmin, Instant ahora) {
        estado = EstadoUsuario.aprobado;
        aprobadoPor = idAdmin;
        aprobadoAt = ahora;
    }

    public Integer getAprobadoPor() { return aprobadoPor; }
    public Instant getAprobadoAt() { return aprobadoAt; }
    public short getIntentosFallidos() { return intentosFallidos; }
    public Instant getBloqueoLoginHasta() { return bloqueoLoginHasta; }

    public Integer getId() { return id; }
    public String getUsername() { return username; }
    public String getNombre() { return nombre; }
    public String getCorreo() { return correo; }
    public String getPasswordHash() { return passwordHash; }
    public Rol getRol() { return rol; }
    public EstadoUsuario getEstado() { return estado; }
    public Instant getFechaRegistro() { return fechaRegistro; }
    public Instant getUltimoLogin() { return ultimoLogin; }
    public Instant getEliminadoAt() { return eliminadoAt; }

    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setRol(Rol rol) { this.rol = rol; }
    public void setEstado(EstadoUsuario estado) { this.estado = estado; }
    public void setUltimoLogin(Instant ultimoLogin) { this.ultimoLogin = ultimoLogin; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public void setCorreo(String correo) { this.correo = correo; }

    /** Borrado lógico: ya no puede entrar, pero sus quinielas e historial se conservan. */
    public void eliminar(Instant ahora) {
        this.eliminadoAt = ahora;
    }
}
