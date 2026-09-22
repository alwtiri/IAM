package com.enterprise.iam.core.shared.infrastructure.security;

import com.enterprise.iam.core.shared.api.security.WorkerPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Internal API listener for worker-plane components (SECURITY-ARCHITECTURE §2, PHASE-3-DESIGN §6).
 *
 * <ul>
 *   <li>A second Tomcat connector with TLS 1.2+/1.3 and <b>required</b> client certificates signed by the internal CA.</li>
 *   <li>{@code /internal/**} is served only on that connector and only to certificates whose CN is a registered worker;
 *       anything else gets 404. The public listener and the proxy never reach it.</li>
 *   <li>Stateless, no CSRF (no browser involved), no session.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalApiProperties.class)
class InternalApiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InternalApiConfiguration.class);

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalApiConnector(InternalApiProperties p) {
        return factory -> {
            if (!p.enabled()) {
                return;
            }
            Connector connector = new Connector(Http11NioProtocol.class.getName());
            connector.setPort(p.port());
            connector.setScheme("https");
            connector.setSecure(true);
            Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
            protocol.setSSLEnabled(true);
            SSLHostConfig ssl = new SSLHostConfig();
            ssl.setProtocols("TLSv1.2+TLSv1.3");
            ssl.setCertificateVerification("required");
            ssl.setCaCertificateFile(p.caFile());
            SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
            cert.setCertificateFile(p.certFile());
            cert.setCertificateKeyFile(p.keyFile());
            ssl.addCertificate(cert);
            protocol.addSslHostConfig(ssl);
            factory.addAdditionalConnectors(connector);
            log.info("Internal mTLS listener enabled on port {} for {} registered worker identities", p.port(), p.workers().size());
        };
    }

    /** Separate, first-matching chain: the browser/API chain never sees /internal/** requests. */
    @Bean
    @Order(0)
    SecurityFilterChain internalApiFilterChain(HttpSecurity http, InternalApiProperties p) throws Exception {
        http.securityMatcher("/internal/**")
            .authorizeHttpRequests(a -> a.anyRequest().permitAll()) // authorization = InternalWorkerFilter + @InternalEndpoint
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(r -> r.disable())
            .addFilterBefore(new InternalWorkerFilter(p), AuthorizationFilter.class)
            .headers(h -> h.cacheControl(c -> { }));
        return http.build();
    }

    /** Authenticates the worker from the verified client certificate; everything else on /internal/** answers 404. */
    static final class InternalWorkerFilter extends OncePerRequestFilter {

        private final InternalApiProperties p;

        InternalWorkerFilter(InternalApiProperties p) {
            this.p = p;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!p.enabled() || request.getLocalPort() != p.port() || !request.isSecure()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
            String cn = certs == null || certs.length == 0 ? null : commonName(certs[0]);
            Set<String> types = cn == null ? null : p.workers().get(cn);
            if (types == null) {
                log.warn("Internal API request with unregistered client certificate CN={}", cn);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            request.setAttribute(WorkerPrincipal.REQUEST_ATTRIBUTE, new WorkerPrincipal(cn, types));
            chain.doFilter(request, response);
        }

        static String commonName(X509Certificate cert) {
            try {
                for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                    if ("CN".equalsIgnoreCase(rdn.getType())) {
                        return rdn.getValue().toString();
                    }
                }
            } catch (InvalidNameException e) {
                return null;
            }
            return null;
        }
    }
}
