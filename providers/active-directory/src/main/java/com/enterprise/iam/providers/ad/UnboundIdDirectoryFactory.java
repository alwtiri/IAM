package com.enterprise.iam.providers.ad;

import com.enterprise.iam.kernel.Secret;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.ExtendedResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.SSLUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * LDAP client on the UnboundID LDAP SDK. TLS is mandatory unless the instance is an explicit lab setup; the server
 * certificate is validated against the configured CA (or the JVM trust store, or a pinned SHA-256) and its host name is
 * verified. The bind password is used from memory only and cleared after the bind.
 */
final class UnboundIdDirectoryFactory implements LdapDirectoryFactory {

    @Override
    public LdapDirectory open(Config config, Secret password) throws IOException {
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis((int) config.timeout().toMillis());
        options.setResponseTimeoutMillis(config.timeout().toMillis());
        options.setFollowReferrals(false);
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
        LDAPConnection conn;
        try {
            SSLUtil ssl = config.security() == Security.PLAIN ? null : new SSLUtil(trustManager(config));
            if (config.security() == Security.LDAPS) {
                conn = new LDAPConnection(ssl.createSSLSocketFactory(), options, config.host(), config.port());
            } else {
                conn = new LDAPConnection(options, config.host(), config.port());
                if (config.security() == Security.START_TLS) {
                    ExtendedResult r = conn.processExtendedOperation(new StartTLSExtendedRequest(ssl.createSSLContext()));
                    if (r.getResultCode() != ResultCode.SUCCESS) {
                        conn.close();
                        throw new LdapDirectory.AuthenticationException("StartTLS was refused: " + r.getResultCode().getName());
                    }
                }
            }
        } catch (GeneralSecurityException e) {
            throw new LdapDirectory.AuthenticationException("TLS setup failed: " + e.getClass().getSimpleName());
        } catch (LDAPException e) {
            throw connectFailure(config, e);
        }
        char[] chars = password.reveal(); // provider credential use: bind only, cleared below
        byte[] bytes = new String(chars).getBytes(StandardCharsets.UTF_8);
        try {
            conn.bind(new SimpleBindRequest(config.bindDn(), bytes));
        } catch (LDAPException e) {
            conn.close();
            if (e.getResultCode() == ResultCode.INVALID_CREDENTIALS) {
                throw new LdapDirectory.AuthenticationException("bind rejected for " + config.bindDn());
            }
            throw connectFailure(config, e);
        } finally {
            Arrays.fill(chars, '\0');
            Arrays.fill(bytes, (byte) 0);
        }
        return new Directory(conn);
    }

    private static IOException connectFailure(Config config, LDAPException e) {
        String where = config.host() + ":" + config.port();
        if (e.getCause() instanceof javax.net.ssl.SSLException || e.getResultCode() == ResultCode.LOCAL_ERROR) {
            return new LdapDirectory.AuthenticationException("TLS handshake or certificate verification failed for " + where);
        }
        return new LdapDirectory.ConnectionException("cannot connect to " + where + " (" + e.getResultCode().getName() + ")");
    }

    private static TrustManager trustManager(Config config) throws GeneralSecurityException {
        if (config.pinnedCertificateSha256() != null) {
            return new PinnedTrustManager(config.pinnedCertificateSha256());
        }
        if (config.caCertificatesPem() == null) {
            return JVMDefaultTrustManager.getInstance();
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            store.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException("cannot initialise trust store", e);
        }
        int i = 0;
        for (Certificate c : CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(config.caCertificatesPem().getBytes(StandardCharsets.US_ASCII)))) {
            store.setCertificateEntry("ca-" + i++, c);
        }
        if (i == 0) {
            throw new CertificateException("caCertificatePem contains no certificate");
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return tm;
            }
        }
        throw new GeneralSecurityException("no X509 trust manager available");
    }

    /** Accepts exactly one server certificate, identified by the SHA-256 of its DER encoding. */
    private static final class PinnedTrustManager implements X509TrustManager {
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
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(expected, actual)) {
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

    private record Directory(LDAPConnection conn) implements LdapDirectory {

        @Override
        public Entry rootDse(List<String> attributes) throws IOException {
            SearchPage p = search("", Scope.BASE, "(objectClass=*)", attributes, 0, null);
            if (p.entries().isEmpty()) {
                throw new RejectedException("noSuchObject", "root DSE not readable");
            }
            return p.entries().get(0);
        }

        @Override
        public SearchPage search(String baseDn, Scope scope, String filter, List<String> attributes, int pageSize, byte[] cookie)
                throws IOException {
            try {
                SearchRequest req = new SearchRequest(baseDn, scope == Scope.BASE ? SearchScope.BASE : SearchScope.SUB, filter,
                        attributes.toArray(new String[0]));
                if (pageSize > 0 && scope == Scope.SUBTREE) {
                    req.setControls(new SimplePagedResultsControl(pageSize, cookie == null ? null : new ASN1OctetString(cookie)));
                }
                req.setTimeLimitSeconds((int) Math.max(1, conn.getConnectionOptions().getResponseTimeoutMillis() / 1000));
                SearchResult result = conn.search(req);
                List<Entry> entries = new ArrayList<>(result.getEntryCount());
                for (SearchResultEntry e : result.getSearchEntries()) {
                    entries.add(convert(e));
                }
                byte[] next = null;
                SimplePagedResultsControl paged = SimplePagedResultsControl.get(result);
                if (paged != null && paged.moreResultsToReturn()) {
                    next = paged.getCookie().getValue();
                }
                return new SearchPage(entries, next);
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return new SearchPage(List.of(), null);
                }
                throw failure(e, false);
            }
        }

        @Override
        public void replace(String dn, String attribute, String value) throws IOException {
            try {
                conn.modify(dn, new Modification(ModificationType.REPLACE, attribute, value));
            } catch (LDAPException e) {
                throw failure(e, true);
            }
        }

        @Override
        public void replaceBinary(String dn, String attribute, byte[] value) throws IOException {
            try {
                conn.modify(dn, new Modification(ModificationType.REPLACE, attribute, value));
            } catch (LDAPException e) {
                throw failure(e, true);
            }
        }

        @Override
        public void close() {
            conn.close();
        }

        private static IOException failure(LDAPException e, boolean write) {
            ResultCode rc = e.getResultCode();
            if (rc == ResultCode.TIMEOUT || (write && rc == ResultCode.SERVER_DOWN)) {
                return new TimeoutException("no response from the directory (" + rc.getName() + ")");
            }
            if (rc == ResultCode.SERVER_DOWN || rc == ResultCode.CONNECT_ERROR) {
                return new ConnectionException("connection to the directory lost (" + rc.getName() + ")");
            }
            return new RejectedException(rc.getName(), rc.getName());
        }

        private static Entry convert(SearchResultEntry e) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            for (Attribute a : e.getAttributes()) {
                if ("objectGUID".equalsIgnoreCase(a.getName())) {
                    String g = AdValues.guid(a.getValueByteArray());
                    if (g != null) {
                        values.put("objectGUID", List.of(g));
                    }
                } else {
                    values.put(a.getName(), List.of(a.getValues()));
                }
            }
            return new Entry(e.getDN(), values);
        }
    }
}
