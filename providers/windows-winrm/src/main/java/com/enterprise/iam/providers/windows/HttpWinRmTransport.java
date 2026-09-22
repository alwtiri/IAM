package com.enterprise.iam.providers.windows;

import com.enterprise.iam.kernel.Secret;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * WS-Management (WinRM) client over HTTPS with Basic authentication: create shell → run
 * {@code powershell -EncodedCommand} → receive until done → terminate → delete shell. HTTP (5985) is refused because Basic
 * would expose the password. Server certificates are verified against the configured CA, a pinned SHA-256, or the JVM
 * trust store, including host name verification. XML responses are parsed with DTDs disabled (no XXE).
 */
final class HttpWinRmTransport implements WinRmTransport {

    static final String NS_SOAP = "http://www.w3.org/2003/05/soap-envelope";
    static final String NS_SHELL = "http://schemas.microsoft.com/wbem/wsman/1/windows/shell";
    static final String NS_WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";
    private static final String CMD_RESOURCE = "http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd";
    private static final String DONE = NS_SHELL + "/CommandState/Done";
    private static final int MAX_RECEIVES = 2000;

    @Override
    public Result run(Endpoint ep, Secret password, String script, String parametersJson) throws IOException {
        URI uri = URI.create(ep.url());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new AuthenticationException("WinRM endpoint must use https:// (Basic authentication over HTTP is refused)");
        }
        HttpClient http;
        try {
            // java.net.http always verifies the host name, also for pinned certificates (the certificate must name the host).
            http = HttpClient.newBuilder().connectTimeout(ep.timeout()).followRedirects(HttpClient.Redirect.NEVER)
                    .sslContext(sslContext(ep)).build();
        } catch (GeneralSecurityException e) {
            throw new AuthenticationException("TLS setup failed: " + e.getClass().getSimpleName());
        }
        char[] pw = password.reveal(); // provider credential use: HTTP Basic header only, cleared below
        String auth;
        try {
            byte[] raw = (ep.username() + ":" + new String(pw)).getBytes(StandardCharsets.UTF_8);
            auth = "Basic " + Base64.getEncoder().encodeToString(raw);
            Arrays.fill(raw, (byte) 0);
        } finally {
            Arrays.fill(pw, '\0');
        }
        Session s = new Session(http, uri, auth, ep);
        String shellId = s.createShell();
        try {
            String commandId = s.command(shellId, "powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "
                    + encode(wrap(script, parametersJson)));
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            int exit = -1;
            for (int i = 0; i < MAX_RECEIVES; i++) {
                Document d = s.receive(shellId, commandId);
                if (d == null) {
                    continue; // WS-Man operation timeout without output: poll again
                }
                NodeList streams = d.getElementsByTagNameNS(NS_SHELL, "Stream");
                for (int k = 0; k < streams.getLength(); k++) {
                    Element st = (Element) streams.item(k);
                    String text = st.getTextContent().trim();
                    if (!text.isEmpty()) {
                        String chunk = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
                        ("stderr".equals(st.getAttribute("Name")) ? err : out).append(chunk);
                    }
                }
                NodeList state = d.getElementsByTagNameNS(NS_SHELL, "CommandState");
                if (state.getLength() > 0 && DONE.equals(((Element) state.item(0)).getAttribute("State"))) {
                    NodeList code = d.getElementsByTagNameNS(NS_SHELL, "ExitCode");
                    exit = code.getLength() > 0 ? Integer.parseInt(code.item(0).getTextContent().trim()) : 0;
                    break;
                }
            }
            s.signalTerminate(shellId, commandId);
            return new Result(exit, out.toString(), err.toString());
        } finally {
            s.deleteShell(shellId);
        }
    }

    /** Prologue: strict errors, UTF-8 output, parameters decoded from base64 JSON (never interpolated into code). */
    static String wrap(String script, String parametersJson) {
        String b64 = Base64.getEncoder().encodeToString((parametersJson == null ? "{}" : parametersJson).getBytes(StandardCharsets.UTF_8));
        return "$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "$p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64 + "'))|ConvertFrom-Json;"
                + "try{\n" + script + "\n}catch{[Console]::Error.WriteLine($_.Exception.Message);exit 1}";
    }

    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static SSLContext sslContext(Endpoint ep) throws GeneralSecurityException {
        TrustManager tm;
        if (ep.pinnedCertificateSha256() != null) {
            tm = new PinnedTrustManager(ep.pinnedCertificateSha256());
        } else if (ep.caCertificatesPem() != null) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            try {
                store.load(null, null);
            } catch (IOException e) {
                throw new GeneralSecurityException("cannot initialise trust store", e);
            }
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(ep.caCertificatesPem().getBytes(StandardCharsets.US_ASCII)))) {
                store.setCertificateEntry("ca-" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            tm = tmf.getTrustManagers()[0];
        } else {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            tm = tmf.getTrustManagers()[0];
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
        return ctx;
    }

    /** Accepts exactly one server certificate, identified by the SHA-256 of its DER encoding (self-signed WinRM listeners). */
    static final class PinnedTrustManager implements X509TrustManager {
        private final byte[] expected;

        PinnedTrustManager(String hex) {
            this.expected = HexFormat.of().parseHex(hex.replace(":", "").trim().toLowerCase(Locale.ROOT));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not validated here");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("no server certificate");
            }
            try {
                if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))) {
                    throw new CertificateException("server certificate does not match the pinned SHA-256");
                }
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new CertificateException(e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** One WS-Man conversation. */
    private record Session(HttpClient http, URI uri, String auth, Endpoint ep) {

        String createShell() throws IOException {
            Document d = post(envelope("http://schemas.xmlsoap.org/ws/2004/09/transfer/Create", null,
                    "<w:OptionSet><w:Option Name=\"WINRS_NOPROFILE\">TRUE</w:Option><w:Option Name=\"WINRS_CODEPAGE\">65001</w:Option></w:OptionSet>",
                    "<rsp:Shell><rsp:InputStreams>stdin</rsp:InputStreams><rsp:OutputStreams>stdout stderr</rsp:OutputStreams></rsp:Shell>"), false);
            NodeList sel = d.getElementsByTagNameNS(NS_WSMAN, "Selector");
            for (int i = 0; i < sel.getLength(); i++) {
                Element e = (Element) sel.item(i);
                if ("ShellId".equals(e.getAttribute("Name"))) {
                    return e.getTextContent().trim();
                }
            }
            NodeList id = d.getElementsByTagNameNS(NS_SHELL, "ShellId");
            if (id.getLength() > 0) {
                return id.item(0).getTextContent().trim();
            }
            throw new IOException("WinRM did not return a shell id");
        }

        String command(String shellId, String command, String args) throws IOException {
            Document d = post(envelope(NS_SHELL + "/Command", shellId,
                    "<w:OptionSet><w:Option Name=\"WINRS_CONSOLEMODE_STDIN\">TRUE</w:Option><w:Option Name=\"WINRS_SKIP_CMD_SHELL\">TRUE</w:Option></w:OptionSet>",
                    "<rsp:CommandLine><rsp:Command>" + xml(command) + "</rsp:Command><rsp:Arguments>" + xml(args) + "</rsp:Arguments></rsp:CommandLine>"), false);
            NodeList id = d.getElementsByTagNameNS(NS_SHELL, "CommandId");
            if (id.getLength() == 0) {
                throw new IOException("WinRM did not return a command id");
            }
            return id.item(0).getTextContent().trim();
        }

        /** Returns null when the server answered with an operation timeout (no output yet). */
        Document receive(String shellId, String commandId) throws IOException {
            return post(envelope(NS_SHELL + "/Receive", shellId, null,
                    "<rsp:Receive><rsp:DesiredStream CommandId=\"" + xml(commandId) + "\">stdout stderr</rsp:DesiredStream></rsp:Receive>"), true);
        }

        void signalTerminate(String shellId, String commandId) {
            try {
                post(envelope(NS_SHELL + "/Signal", shellId, null, "<rsp:Signal CommandId=\"" + xml(commandId) + "\"><rsp:Code>"
                        + NS_SHELL + "/signal/terminate</rsp:Code></rsp:Signal>"), true);
            } catch (IOException e) {
                // best effort
            }
        }

        void deleteShell(String shellId) {
            try {
                post(envelope("http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete", shellId, null, ""), true);
            } catch (IOException e) {
                // best effort; the server expires idle shells
            }
        }

        private String envelope(String action, String shellId, String options, String body) {
            return "<s:Envelope xmlns:s=\"" + NS_SOAP + "\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:w=\"" + NS_WSMAN
                    + "\" xmlns:rsp=\"" + NS_SHELL + "\"><s:Header>"
                    + "<a:To>" + xml(uri.toString()) + "</a:To>"
                    + "<w:ResourceURI s:mustUnderstand=\"true\">" + CMD_RESOURCE + "</w:ResourceURI>"
                    + "<a:ReplyTo><a:Address s:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>"
                    + "<a:Action s:mustUnderstand=\"true\">" + action + "</a:Action>"
                    + "<w:MaxEnvelopeSize s:mustUnderstand=\"true\">512000</w:MaxEnvelopeSize>"
                    + "<a:MessageID>uuid:" + UUID.randomUUID() + "</a:MessageID>"
                    + "<w:Locale xml:lang=\"en-US\" s:mustUnderstand=\"false\"/>"
                    + "<w:OperationTimeout>PT20S</w:OperationTimeout>"
                    + (shellId == null ? "" : "<w:SelectorSet><w:Selector Name=\"ShellId\">" + xml(shellId) + "</w:Selector></w:SelectorSet>")
                    + (options == null ? "" : options)
                    + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>";
        }

        private Document post(String envelope, boolean timeoutFaultIsEmpty) throws IOException {
            HttpResponse<byte[]> r;
            try {
                r = http.send(HttpRequest.newBuilder(uri).timeout(ep.timeout().plusSeconds(25)).header("Authorization", auth)
                        .header("Content-Type", "application/soap+xml;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (SSLException e) {
                throw new AuthenticationException("TLS handshake or certificate verification failed for " + uri.getHost());
            } catch (ConnectException | HttpTimeoutException e) {
                throw new ConnectionException("cannot connect to " + uri.getHost() + ":" + uri.getPort() + " (" + e.getClass().getSimpleName() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                throw new AuthenticationException("WinRM rejected the credentials (HTTP " + r.statusCode() + "); Basic authentication must be enabled for the listener");
            }
            Document d = parse(r.body());
            if (r.statusCode() == 500 && d != null && timeoutFaultIsEmpty && isTimeoutFault(d)) {
                return null;
            }
            if (r.statusCode() != 200 || d == null) {
                throw new IOException("WinRM answered HTTP " + r.statusCode() + faultText(d));
            }
            return d;
        }

        private static boolean isTimeoutFault(Document d) {
            NodeList wf = d.getElementsByTagNameNS("http://schemas.microsoft.com/wbem/wsman/1/wsmanfault", "WSManFault");
            if (wf.getLength() > 0 && "2150858793".equals(((Element) wf.item(0)).getAttribute("Code"))) {
                return true;
            }
            NodeList sub = d.getElementsByTagNameNS(NS_SOAP, "Value");
            for (int i = 0; i < sub.getLength(); i++) {
                if (sub.item(i).getTextContent().trim().endsWith(":TimedOut")) {
                    return true;
                }
            }
            return false;
        }

        private static String faultText(Document d) {
            if (d == null) {
                return "";
            }
            NodeList t = d.getElementsByTagNameNS(NS_SOAP, "Text");
            String s = t.getLength() > 0 ? t.item(0).getTextContent().trim() : "";
            return s.isEmpty() ? "" : ": " + (s.length() > 200 ? s.substring(0, 200) : s);
        }
    }

    static Document parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            return null;
        }
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
