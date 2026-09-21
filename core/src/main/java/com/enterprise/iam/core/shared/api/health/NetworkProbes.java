package com.enterprise.iam.core.shared.api.health;



import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** Framework-free probes for components the Core does not otherwise call on the request path. */
public final class NetworkProbes {

    private NetworkProbes() {
    }

    /** HTTP GET expecting 2xx (e.g. Keycloak JWKS). */
    public static ComponentHealthCheck http(String component, ComponentHealth.Category category,
                                            ComponentHealth.Classification classification, URI uri, List<String> affected) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new Probe(component, category, classification, affected, () -> {
            try {
                HttpResponse<Void> r = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                return r.statusCode() / 100 == 2 ? ComponentHealthCheck.Result.healthy()
                        : ComponentHealthCheck.Result.unavailable("HTTP " + r.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ComponentHealthCheck.Result.unavailable("interrupted");
            } catch (Exception e) {
                return ComponentHealthCheck.Result.unavailable(e.getClass().getSimpleName());
            }
        });
    }

    /** TCP connect and optional banner read (e.g. SMTP "220"). */
    public static ComponentHealthCheck tcp(String component, ComponentHealth.Category category,
                                           ComponentHealth.Classification classification, String host, int port,
                                           String expectedBannerPrefix, List<String> affected) {
        return new Probe(component, category, classification, affected, () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 2000);
                s.setSoTimeout(2000);
                if (expectedBannerPrefix != null) {
                    byte[] buf = new byte[64];
                    int n = s.getInputStream().read(buf);
                    String banner = n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.US_ASCII);
                    if (!banner.startsWith(expectedBannerPrefix)) {
                        return ComponentHealthCheck.Result.degraded("unexpected banner");
                    }
                }
                return ComponentHealthCheck.Result.healthy();
            } catch (Exception e) {
                return ComponentHealthCheck.Result.unavailable(e.getClass().getSimpleName());
            }
        });
    }

    /** Redis/Valkey {@code AUTH} + {@code PING} over RESP with a password supplier (read lazily from a secret file). */
    public static ComponentHealthCheck redis(String host, int port, Supplier<String> password, List<String> affected) {
        return new Probe("cache", ComponentHealth.Category.CACHE, ComponentHealth.Classification.OPTIONAL, affected, () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 2000);
                s.setSoTimeout(2000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                String pw = password.get();
                if (pw != null && !pw.isEmpty()) {
                    out.write(resp("AUTH", pw));
                    if (!readLine(in).startsWith("+OK")) {
                        return ComponentHealthCheck.Result.unavailable("authentication failed");
                    }
                }
                out.write(resp("PING"));
                return readLine(in).startsWith("+PONG") ? ComponentHealthCheck.Result.healthy()
                        : ComponentHealthCheck.Result.unavailable("unexpected reply");
            } catch (Exception e) {
                return ComponentHealthCheck.Result.unavailable(e.getClass().getSimpleName());
            }
        });
    }

    static byte[] resp(String... parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n' && sb.length() < 256) {
            sb.append((char) c);
        }
        return sb.toString();
    }

    private record Probe(String component, ComponentHealth.Category category, ComponentHealth.Classification classification,
                         List<String> affectedFunctionality, Supplier<Result> probe) implements ComponentHealthCheck {
        @Override
        public Result check() {
            return probe.get();
        }
    }
}
