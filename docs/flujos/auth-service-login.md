Flujo completo del POST /auth/login

Diagrama (Mermaid)

sequenceDiagram
autonumber
participant C as Cliente
participant AC as AuthController
participant AS as AuthService
participant AM as AuthenticationManager
participant DS as CustomUserDetailsService
participant UR as UserRepository
participant RT as RefreshTokenRepository
participant JWT as JwtService

    C->>AC: POST /auth/login {email, password}
    AC->>AS: login()
    AS->>AM: authenticate(email, password)
    AM->>DS: loadUserByUsername(email)
    DS->>UR: findByEmail(email)
    UR-->>DS: User (dominio) → UserDetails (adaptado)
    AM-->>AS: Authentication OK / BadCredentialsException
    AS->>UR: findByEmail(email) (segunda consulta)
    AS->>RT: save(new RefreshToken)
    AS->>JWT: generateAccessToken(userId)
    JWT-->>AS: accessToken
    AS-->>AC: LoginResponse
    AC-->>C: 200 OK + JSON

Notas del flujo

🔹 Autoconfiguración de DaoAuthenticationProvider

Tú defines:

CustomUserDetailsService (@Service)

PasswordEncoder (@Bean)

Spring Security detecta esos beans y construye automáticamente un DaoAuthenticationProvider que los usa internamente.

🔹 Doble findByEmail

Durante la autenticación, DaoAuthenticationProvider llama a CustomUserDetailsService.loadUserByUsername(email), que:

hace findByEmail(email)

transforma el User de dominio en UserDetails (email como username, passwordHash, ROLE_USER)

no devuelve la entidad de dominio, sino el adaptador UserDetails.

Después de autenticar, AuthService.login() vuelve a llamar a userRepository.findByEmail() para obtener el User real (necesario para user.getId()).

🔹 Refresh token persistido (opaco)

El refresh token no es un JWT, es un token opaco guardado en BD.

La ventaja clave es revocación server-side: puede invalidarse antes de su exp marcándolo como revoked=true.

El familyId permite detectar reuso y gestionar rotación segura.

El formato opaco no es por "evitar exponer información"; un JWT firmado tampoco expone datos sensibles. La razón real es la revocación.

🔹 Access token con claims mínimos

Incluye únicamente:

sub = userId

iat

exp

Es un token minimalista. La seguridad proviene de la firma HMAC-SHA256 y la expiración.

🔹 Punto de salida

AuthController devuelve el DTO LoginResponse. Spring MVC lo serializa directamente a JSON.

🔹 Flujo de error (credenciales incorrectas)

DaoAuthenticationProvider lanza BadCredentialsException.

El GlobalExceptionHandler la captura.

Respuesta final: 401 Unauthorized.

Pendientes / huecos para próximos días

Documentar /auth/refresh (rotación + familyId).

Endpoint de logout: no existe. La revocación solo ocurre de forma implícita en /auth/refresh (rotación) o al detectar reuso. Gap potencial del diseño: si el cliente pierde el refresh token o quiere cerrar sesión activamente, no puede invalidar el par de tokens en curso.

Documentar /users (registro).

Tests del flujo de login: no verificados aún.
