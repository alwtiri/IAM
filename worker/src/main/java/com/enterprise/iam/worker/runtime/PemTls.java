package com.enterprise.iam.worker.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Builds an mTLS {@link SSLContext} from PEM files (PKCS#8 EC or RSA key), without keystore files on disk. */
public final class PemTls {

    private PemTls() {
    }

    public static SSLContext context(Path certFile, Path keyFile, Path caFile) throws IOException, GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> chain = cf.generateCertificates(new ByteArrayInputStream(Files.readAllBytes(certFile)));
        Collection<? extends Certificate> cas = cf.generateCertificates(new ByteArrayInputStream(Files.readAllBytes(caFile)));
        PrivateKey key = privateKey(Files.readString(keyFile, StandardCharsets.US_ASCII));

        char[] pass = new char[0];
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("client", key, pass, chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);

        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        int i = 0;
        for (Certificate ca : cas) {
            ts.setCertificateEntry("ca" + i++, ca);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        String b64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : new String[]{"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (GeneralSecurityException e) {
                // try the next algorithm
            }
        }
        throw new GeneralSecurityException("unsupported private key (expected PKCS#8 EC or RSA)");
    }
}
