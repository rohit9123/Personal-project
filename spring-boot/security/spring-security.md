# Spring Security — A Complete Guide for Backend Engineers

---

## 1. What is Spring Security?

Spring Security is a framework that handles **authentication** (who are you?) and **authorization** (what are you allowed to do?) for Spring Boot applications. It intercepts every HTTP request through a chain of servlet filters before it reaches your controller.

| Concept | Question it answers | Example |
|---------|-------------------|---------|
| **Authentication** | "Who is this user?" | Verify username/password, validate a JWT token |
| **Authorization** | "Can this user do this?" | User with role `ADMIN` can delete records; `USER` cannot |

### The 10,000-Foot View

```
HTTP Request
     │
     ▼
┌──────────────────────────────────────────┐
│         Spring Security Filter Chain      │
│                                          │
│  ┌─────────────────────────────────┐     │
│  │ 1. SecurityContextHolderFilter  │     │  ← Load SecurityContext (does NOT auto-save in SS6)
│  │ 2. CorsFilter                   │     │  ← CORS headers
│  │ 3. CsrfFilter                   │     │  ← CSRF token validation
│  │ 4. LogoutFilter                 │     │  ← Handle /logout
│  │ 5. UsernamePasswordAuthFilter   │     │  ← Form login (or JwtAuthFilter)
│  │ 6. BasicAuthenticationFilter    │     │  ← HTTP Basic auth
│  │ 7. ExceptionTranslationFilter   │     │  ← Convert security exceptions to HTTP responses
│  │ 8. AuthorizationFilter          │     │  ← Check roles/permissions
│  └─────────────────────────────────┘     │
│                                          │
└──────────────────────────────────────────┘
     │
     ▼ (only if all filters pass)
┌──────────────┐
│  Controller   │
└──────────────┘
```

Every filter in the chain has a specific job. If any filter rejects the request (bad credentials, missing token, insufficient role), the request never reaches your controller.

---

## 2. Why Use Spring Security?

- **Don't roll your own auth**: Custom auth code is the #1 source of security vulnerabilities. Spring Security is battle-tested, handles edge cases (timing attacks, session fixation, CSRF), and is maintained by a dedicated team.
- **Declarative**: Annotate methods with `@PreAuthorize("hasRole('ADMIN')")` instead of writing `if (user.getRole().equals("ADMIN"))` everywhere.
- **Pluggable**: Swap authentication mechanisms (form login → JWT → OAuth2) without changing business logic.
- **Spring ecosystem integration**: Works seamlessly with Spring Boot auto-configuration, Spring Data (for user storage), Spring Session (for distributed sessions), and Spring Cloud Gateway.

---

## 3. Core Architecture — How It Works Under the Hood

### 3.1 The Filter Chain

Spring Security is built on **Servlet Filters**, not Spring MVC interceptors. This is important because filters run *before* the DispatcherServlet, meaning security checks happen before any controller code.

```mermaid
flowchart TD
    Request([HTTP Request]) --> DFP[DelegatingFilterProxy]
    DFP --> FCPS[FilterChainProxy]
    
    FCPS --> SC{Match Security\nFilter Chain?}
    SC -->|/api/**| Chain1[API Filter Chain\nJWT Auth + Stateless]
    SC -->|/admin/**| Chain2[Admin Filter Chain\nForm Login + Session]
    SC -->|/**| Chain3[Default Chain\nPermit All]
    
    Chain1 --> Controller([Controller])
    Chain2 --> Controller
    Chain3 --> Controller
```

**Key classes**:
- **`DelegatingFilterProxy`**: A standard servlet filter registered with the servlet container. It delegates to Spring's `FilterChainProxy`.
- **`FilterChainProxy`**: Holds multiple `SecurityFilterChain` instances. Matches the request URL to the right chain.
- **`SecurityFilterChain`**: An ordered list of filters for a specific URL pattern.

**Why multiple chains?** A real application often has different security needs:
- `/api/**` → stateless JWT authentication (no session)
- `/admin/**` → form-based login with session cookies
- `/public/**` → no authentication needed

### 3.2 Authentication Flow

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Filter as AuthenticationFilter
    participant Manager as AuthenticationManager
    participant Provider as AuthenticationProvider
    participant UserSvc as UserDetailsService
    participant DB as Database
    participant Context as SecurityContextHolder

    Client->>Filter: POST /login {username, password}
    Filter->>Filter: Create UsernamePasswordAuthenticationToken (unauthenticated)
    Filter->>Manager: authenticate(token)
    Manager->>Provider: authenticate(token)
    Provider->>UserSvc: loadUserByUsername("alice")
    UserSvc->>DB: SELECT * FROM users WHERE username='alice'
    DB-->>UserSvc: User record
    UserSvc-->>Provider: UserDetails (username, encoded password, roles)
    Provider->>Provider: passwordEncoder.matches(rawPassword, encodedPassword)
    
    alt Password matches
        Provider-->>Manager: Authenticated Token (with authorities)
        Manager-->>Filter: Authentication success
        Filter->>Context: Store Authentication in SecurityContext
        Filter-->>Client: 200 OK + JWT token (or session cookie)
    else Password mismatch
        Provider-->>Manager: throw BadCredentialsException
        Manager-->>Filter: Authentication failed
        Filter-->>Client: 401 Unauthorized
    end
```

**Key interfaces**:

| Interface | Responsibility | You implement? |
|-----------|---------------|----------------|
| `AuthenticationFilter` | Extracts credentials from request | Rarely (use built-in or custom JWT filter) |
| `AuthenticationManager` | Delegates to providers | No (use default `ProviderManager`) |
| `AuthenticationProvider` | Actual authentication logic | Sometimes (for custom auth like LDAP) |
| `UserDetailsService` | Load user from database | **Yes — almost always** |
| `PasswordEncoder` | Hash and verify passwords | No (use `BCryptPasswordEncoder`) |

### 3.3 SecurityContext and ThreadLocal

After successful authentication, the `Authentication` object is stored in `SecurityContextHolder`:

```java
// How Spring Security stores the authenticated user
SecurityContext context = SecurityContextHolder.getContext();
Authentication auth = context.getAuthentication();

auth.getName();          // "alice"
auth.getAuthorities();   // [ROLE_USER, ROLE_ADMIN]
auth.isAuthenticated();  // true
```

- **Storage**: By default, `SecurityContextHolder` uses `ThreadLocal` — each request thread has its own security context.
- **Pitfall**: If you spawn child threads (e.g., `@Async`), they don't inherit the `SecurityContext`. Fix: use `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` or pass the context explicitly.
- **Cleanup / persistence (Spring Security 6)**: `SecurityContextPersistenceFilter` is **removed** in Spring Security 6; it is replaced by `SecurityContextHolderFilter`. The key behavioral change: `SecurityContextHolderFilter` only **loads** the context at the start of the request and **clears the `SecurityContextHolder` in a `finally` block** when the request completes (preventing thread-pool leaks). It does **not** automatically *save* the context back at the end of the request. If you authenticate mid-request and want it persisted (e.g. to the session), you must save it explicitly via the `SecurityContextRepository` (e.g. `securityContextRepository.saveContext(context, request, response)`).

### 3.4 Authorization — How Roles and Permissions Work

After authentication, authorization checks whether the user *can* access a resource:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
```

**Two levels of authorization**:

| Level | Where | How |
|-------|-------|-----|
| **URL-based** | `SecurityFilterChain` config | `requestMatchers("/admin/**").hasRole("ADMIN")` |
| **Method-based** | On service/controller methods | `@PreAuthorize("hasRole('ADMIN')")` |

**Method-level security** (more granular):

```java
@Service
public class OrderService {

    @PreAuthorize("hasRole('ADMIN')")
    public void deleteOrder(Long orderId) { ... }

    @PreAuthorize("#userId == authentication.principal.id")
    public Order getOrder(Long orderId, Long userId) { ... }

    @PostAuthorize("returnObject.owner == authentication.name")
    public Order findOrder(Long id) { ... }
}
```

- `@PreAuthorize`: Checked *before* method execution. SpEL expression.
- `@PostAuthorize`: Checked *after* method execution — can inspect the return value.
- Enable with `@EnableMethodSecurity` on your config class.

---

## 4. JWT Authentication (Stateless APIs)

### 4.1 Why JWT Over Sessions?

| Aspect | Session-Based | JWT (Stateless) |
|--------|--------------|-----------------|
| Storage | Server stores session in memory/Redis | Server stores nothing — token is self-contained |
| Scalability | Sticky sessions or shared session store | Any server can validate the token |
| Mobile/SPA | Awkward (cookies don't work well cross-origin) | Natural (token in `Authorization` header) |
| Logout | Server destroys session | Harder — token is valid until expiry |
| CSRF | Vulnerable (cookies sent automatically) | Not vulnerable (token is in header, not cookie) |

**Decision**: Use **JWT for REST APIs and microservices**. Use **sessions for server-rendered web apps** (Thymeleaf, JSP).

### 4.2 JWT Structure

```
Header.Payload.Signature

eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSIsInJvbGVzIjpbIlVTRVIiXSwiZXhwIjoxNzE3MjAwMDAwfQ.abc123signature
```

| Part | Contains | Example |
|------|----------|---------|
| **Header** | Algorithm, token type | `{"alg": "HS256", "typ": "JWT"}` |
| **Payload** | Claims (user data) | `{"sub": "alice", "roles": ["USER"], "exp": 1717200000}` |
| **Signature** | HMAC(header + payload, secret) | Prevents tampering |

**Important**: The payload is Base64-encoded, NOT encrypted. Anyone can read it. Never put sensitive data (passwords, SSN) in the payload.

### 4.3 JWT Authentication Flow

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Auth as Auth Controller
    participant JwtUtil as JwtService
    participant API as Protected API
    participant Filter as JwtAuthFilter

    Note over Client, Auth: Step 1: Login
    Client->>Auth: POST /auth/login {username, password}
    Auth->>Auth: Validate credentials via AuthenticationManager
    Auth->>JwtUtil: generateToken(userDetails)
    JwtUtil-->>Auth: JWT access token + refresh token
    Auth-->>Client: { "access_token": "eyJ...", "refresh_token": "eyR..." }

    Note over Client, API: Step 2: Access Protected Resource
    Client->>Filter: GET /api/orders (Authorization: Bearer eyJ...)
    Filter->>JwtUtil: extractUsername(token), isTokenValid(token)
    JwtUtil-->>Filter: Valid, user = "alice"
    Filter->>Filter: Load UserDetails, set SecurityContext
    Filter->>API: Forward request (user authenticated)
    API-->>Client: 200 OK { orders: [...] }

    Note over Client, Auth: Step 3: Token Expired → Refresh
    Client->>Auth: POST /auth/refresh { "refresh_token": "eyR..." }
    Auth->>JwtUtil: validateRefreshToken(), generateNewAccessToken()
    Auth-->>Client: { "access_token": "new_eyJ..." }
```

### 4.4 Implementing JWT in Spring Boot

**Step 1: JwtService — Token generation and validation**

```java
@Service
public class JwtService {
    
    @Value("${jwt.secret}")
    private String secretKey;
    
    private static final long ACCESS_TOKEN_EXPIRY = 15 * 60 * 1000;   // 15 minutes
    private static final long REFRESH_TOKEN_EXPIRY = 7 * 24 * 60 * 60 * 1000; // 7 days

    public String generateAccessToken(UserDetails userDetails) {
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("roles", userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).toList())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRY))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(UserDetails userDetails) {
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_EXPIRY))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
    }
}
```

**Step 2: JwtAuthenticationFilter — Intercept every request**

```java
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        
        // 1. Extract token from Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);  // No token → skip, let other filters handle
            return;
        }
        String jwt = authHeader.substring(7);

        // 2. Extract username and validate
        String username = jwtService.extractUsername(jwt);
        
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            
            if (jwtService.isTokenValid(jwt, userDetails)) {
                // 3. Create authentication token and set in SecurityContext
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        
        // 4. Continue filter chain
        filterChain.doFilter(request, response);
    }
}
```

> [!TIP]
> **JWT Exception Handling**: By default, if the token is expired or malformed, `jjwt` throws `ExpiredJwtException` or `SignatureException` during parsing inside the filter. Since filters execute outside Spring MVC's `@ControllerAdvice` scope, these exceptions propagate as raw 500 Server Errors. To return a clean 401 Unauthorized, inject the standard Spring `HandlerExceptionResolver` and delegate to it:
> ```java
> @Autowired
> @Qualifier("handlerExceptionResolver")
> private HandlerExceptionResolver resolver;
> 
> // Inside doFilterInternal:
> try {
>     // extract & validate token
> } catch (ExpiredJwtException | SignatureException e) {
>     resolver.resolveException(request, response, null, e);
> }
> ```

**Step 3: SecurityConfig — Wire it all together**

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())                        // Disable CSRF for stateless APIs
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))  // No sessions
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()          // Login/register endpoints
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) 
            throws Exception {
        return config.getAuthenticationManager();
    }
}
```

**Step 4: AuthController — Login endpoint**

```java
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        // Authenticate via AuthenticationManager → DaoAuthenticationProvider → UserDetailsService
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        return ResponseEntity.ok(new AuthResponse(accessToken, refreshToken));
    }
}
```

---

## 5. OAuth2 — "Login with Google/GitHub"

### 5.1 What is OAuth2?

OAuth2 is a protocol that lets users log into your app using a third-party provider (Google, GitHub, Facebook) without sharing their password with you.

**Roles**:

| Role | Who | Example |
|------|-----|---------|
| **Resource Owner** | The user | Alice |
| **Client** | Your application | MyApp |
| **Authorization Server** | Issues tokens | Google's OAuth server |
| **Resource Server** | Holds user data | Google's user info API |

### 5.2 Authorization Code Flow (Most Common)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as Your App (Client)
    participant Google as Google Auth Server
    participant API as Google User Info API

    User->>App: Click "Login with Google"
    App->>Google: Redirect to Google login page<br/>(client_id, redirect_uri, scope)
    Google->>User: Show Google login form
    User->>Google: Enter credentials, consent to scopes
    Google->>App: Redirect to callback URL with authorization_code
    
    App->>Google: Exchange code for tokens<br/>POST /token (code, client_secret)
    Google-->>App: { access_token, id_token, refresh_token }
    
    App->>API: GET /userinfo (Bearer access_token)
    API-->>App: { email, name, picture }
    
    App->>App: Create/update local user, issue session/JWT
    App-->>User: Logged in!
```

### 5.3 Spring Boot OAuth2 Client Setup

```yaml
# application.yml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid, profile, email
          github:
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
            scope: read:user, user:email
```

```java
@Configuration
@EnableWebSecurity
public class OAuth2SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth -> oauth
                .userInfoEndpoint(info -> info
                    .userService(customOAuth2UserService())  // Custom user mapping
                )
                .successHandler(oAuth2SuccessHandler())      // Issue JWT after OAuth2 login
            );
        return http.build();
    }

    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> customOAuth2UserService() {
        return new CustomOAuth2UserService();  // Map Google/GitHub user to your User entity
    }
}
```

```java
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(request);
        
        String provider = request.getClientRegistration().getRegistrationId(); // "google"
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");

        // Create or update user in local database
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> User.builder()
                        .email(email)
                        .name(name)
                        .provider(provider)
                        .role(Role.USER)
                        .build());
        
        userRepository.save(user);
        return oAuth2User;
    }
}

> [!WARNING]
> **User Authority Mapping**: Returning `oAuth2User` directly means the user's local roles (e.g. `ROLE_ADMIN`) stored in your database will not be loaded into the `SecurityContext`. To fix this, create a custom class (e.g. `SecureUserPrincipal`) that implements both `UserDetails` and `OAuth2User`, and return it from `loadUser()` mapping your database roles to GrantedAuthorities.
```

---

## 6. CSRF, CORS, and Common Security Headers

### 6.1 CSRF (Cross-Site Request Forgery)

**Attack**: A malicious website tricks your browser into making a request to your app (using your session cookie). Since the cookie is sent automatically, the server thinks it's you.

**Protection**: Spring Security generates a CSRF token. Every state-changing request (POST, PUT, DELETE) must include this token.

```
// Spring auto-includes CSRF token in forms (Thymeleaf)
<form method="post" action="/transfer">
    <input type="hidden" name="_csrf" value="a1b2c3d4-token" />
    ...
</form>
```

**When to disable CSRF**: For stateless REST APIs using JWT. Since JWT is sent in the `Authorization` header (not a cookie), CSRF attacks don't apply — the attacker's site can't set the header.

```java
http.csrf(csrf -> csrf.disable());  // Safe for stateless JWT APIs
```

### 6.2 CORS (Cross-Origin Resource Sharing)

**Problem**: Browser blocks `fetch("https://api.myapp.com")` from `https://frontend.myapp.com` because they're different origins.

```java
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("https://frontend.myapp.com"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

### 6.3 Password Storage — Why BCrypt?

**Never store plaintext passwords.** Use `BCryptPasswordEncoder`:

```java
PasswordEncoder encoder = new BCryptPasswordEncoder();

String hashed = encoder.encode("myPassword123");
// $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy

boolean matches = encoder.matches("myPassword123", hashed);  // true
```

**Why BCrypt over SHA-256?**
- BCrypt is intentionally **slow** (configurable work factor). Brute-forcing is infeasible.
- BCrypt includes a **salt** automatically — same password produces different hashes.
- SHA-256 is fast (designed for checksums, not passwords). An attacker can try billions of SHA-256 hashes per second on a GPU.

---

## 7. SDE-2 Interview Angles

### Q1: "Explain the Spring Security filter chain. How does a request flow through it?"

**Answer**: "Every HTTP request passes through a chain of servlet filters before reaching the controller. The `DelegatingFilterProxy` delegates to Spring's `FilterChainProxy`, which matches the request URL to the appropriate `SecurityFilterChain`. Each chain has ordered filters: `SecurityContextHolderFilter` loads the security context (in Spring Security 6 it replaces the old `SecurityContextPersistenceFilter` and no longer auto-saves the context — you persist explicitly via the `SecurityContextRepository`), `CsrfFilter` validates CSRF tokens, the authentication filter (e.g., `JwtAuthenticationFilter`) validates credentials and populates the `SecurityContext` via `SecurityContextHolder`, and finally `AuthorizationFilter` checks if the authenticated user has the required roles. If any filter rejects the request, a `401` or `403` is returned without hitting the controller. You can have multiple chains — for example, `/api/**` uses JWT stateless auth while `/admin/**` uses form login with sessions."

### Q2: "How would you implement JWT refresh tokens? What happens when the access token expires?"

**Answer**: "I use a dual-token strategy: a short-lived access token (15 minutes) and a long-lived refresh token (7 days). The access token is sent in the `Authorization: Bearer` header for every API call. When it expires, the client sends the refresh token to a `/auth/refresh` endpoint. The server validates the refresh token (checking signature, expiry, and that it hasn't been revoked), then issues a new access token without requiring the user to re-login. Refresh tokens are stored in the database with a `revoked` flag — on logout, we revoke all refresh tokens for that user. This way, even if an access token is stolen, it's only valid for 15 minutes. If the refresh token is stolen, we can revoke it server-side."

### Q3: "JWT is stateless, so how do you handle logout?"

**Answer**: "This is the fundamental trade-off of JWT. Since the server doesn't store session state, you can't 'invalidate' a JWT — it's valid until it expires. Three approaches:

1. **Short expiry + refresh token revocation** (recommended): Access tokens expire in 15 minutes. On logout, revoke the refresh token in the database. The user can't get new access tokens.

2. **Token blacklist**: Store revoked JWT IDs (`jti` claim) in Redis with TTL = token's remaining lifetime. On every request, check the blacklist. Adds statefulness but gives immediate logout.

3. **Token versioning**: Store a `tokenVersion` per user in the database. Include it in the JWT. On logout, increment `tokenVersion`. All existing tokens with the old version are invalid. Requires a DB lookup per request."

### Q4: "What's the difference between `@Secured`, `@PreAuthorize`, and URL-based authorization?"

**Answer**:

| Annotation | SpEL Support | Granularity | Example |
|-----------|-------------|-------------|---------|
| URL-based (`requestMatchers`) | No | Coarse (URL patterns) | `.requestMatchers("/admin/**").hasRole("ADMIN")` |
| `@Secured` | No | Method-level | `@Secured("ROLE_ADMIN")` |
| `@PreAuthorize` | **Yes** | Method-level + expressions | `@PreAuthorize("#userId == authentication.principal.id")` |

"I prefer `@PreAuthorize` because SpEL expressions let you write fine-grained rules like 'users can only access their own data' (`#userId == authentication.principal.id`). URL-based security is good as the first line of defense, but method-level security is essential for business rule enforcement. `@Secured` is legacy — it only supports role names, no expressions."

### Q5: "How would you secure a microservices architecture?"

**Answer**: "In a microservices setup:

1. **API Gateway** handles authentication: validates JWT (or session) and extracts user info. Downstream services receive the user context via headers (e.g., `X-User-Id`, `X-User-Roles`) or a propagated JWT.

2. **Service-to-service auth**: Internal calls use mutual TLS (mTLS) or a service-level JWT issued by an internal auth server. Never trust raw HTTP between services.

3. **Centralized auth service**: One microservice owns user registration, login, token issuance. Other services validate tokens using the shared public key (asymmetric JWT with RS256 instead of HS256 — no shared secret).

4. **Token propagation**: When Service A calls Service B on behalf of a user, propagate the user's JWT in the `Authorization` header. Service B validates it against the same public key and applies its own authorization rules.

5. **Short-lived tokens + centralized revocation**: Access tokens are 5-15 minutes. A Redis-based revocation list is shared across services for immediate logout."

### Q6: "HS256 vs RS256 for JWT signing — when to use which?"

**Answer**:

| Algorithm | Type | Key | Use case |
|-----------|------|-----|----------|
| **HS256** | Symmetric | Single shared secret | Monolith — same server signs and verifies |
| **RS256** | Asymmetric | Private key signs, public key verifies | Microservices — auth service signs, all services verify with public key |

"In a monolith, HS256 is simpler — one secret key does both signing and verification. In microservices, RS256 is essential: the auth service holds the private key (never shared), and all other services verify tokens using the public key. If a service is compromised, the attacker can verify tokens but can't forge new ones. With HS256, a compromised shared secret means the attacker can create tokens for any user."

### Q7: "How do you prevent common security vulnerabilities in a Spring Boot API?"

| Vulnerability | Prevention |
|---------------|------------|
| **SQL Injection** | Use Spring Data JPA (parameterized queries). Never concatenate user input into SQL strings. |
| **XSS** | Set `Content-Type: application/json` (browsers don't execute JSON). For HTML responses, use Thymeleaf's auto-escaping. |
| **CSRF** | Disable for stateless JWT APIs. Enable for session-based apps (Spring Security enables it by default). |
| **Brute Force** | Rate-limit `/auth/login` (e.g., 5 attempts per minute per IP). Lock account after 10 failures. |
| **Token Leakage** | Use HTTPS everywhere. Set `Secure` and `HttpOnly` flags on cookies. Short access token expiry. |
| **Mass Assignment** | Use DTOs, not entities, in request bodies. Never bind `@RequestBody` directly to JPA entities. |
| **Broken Access Control** | Use `@PreAuthorize` to enforce "users can only access their own resources." Test authorization in integration tests. |
