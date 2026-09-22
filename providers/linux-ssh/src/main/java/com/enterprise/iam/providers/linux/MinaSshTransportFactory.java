package com.enterprise.iam.providers.linux;

import com.enterprise.iam.kernel.Secret;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.security.SecurityUtils;

/**
 * SSH transport on Apache MINA SSHD. One started client per worker; one session per operation. The server host key is
 * verified against the configured SHA-256 fingerprint (no trust-on-first-use unless the instance explicitly allows it).
 * Credentials are used from memory only and cleared after authentication.
 */
public final class MinaSshTransportFactory implements SshTransportFactory {

    /** Expected host-key fingerprint, passed per connection so it is known during key exchange ("*" = lab: any key). */
    static final AttributeRepository.AttributeKey<String> EXPECTED_HOST_KEY = new AttributeRepository.AttributeKey<>();

    private static final SshClient CLIENT = startClient();

    private static SshClient startClient() {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, remote, serverKey) -> {
            AttributeRepository ctx = session.getConnectionContext();
            String expected = ctx == null ? null : ctx.getAttribute(EXPECTED_HOST_KEY);
            return expected != null && ("*".equals(expected) || expected.equals(KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)));
        });
        client.start();
        return client;
    }

    @Override
    public SshTransport open(String host, int port, String username, Secret credential, boolean keyAuth, String hostKeyFingerprint,
                             Duration timeout) throws IOException {
        ClientSession session;
        try {
            session = CLIENT.connect(username, host, port,
                    AttributeRepository.ofKeyValuePair(EXPECTED_HOST_KEY, hostKeyFingerprint == null ? "*" : hostKeyFingerprint))
                    .verify(timeout).getSession();
        } catch (IOException e) {
            throw new SshTransport.ConnectionException("cannot connect to " + host + ":" + port + " (" + e.getClass().getSimpleName() + ")");
        }
        try {
            char[] value = credential.reveal(); // provider credential use: authentication only, cleared below
            try {
                if (keyAuth) {
                    byte[] pem = new String(value).getBytes(StandardCharsets.UTF_8);
                    try {
                        Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(session, NamedResource.ofName("provider-credential"),
                                new ByteArrayInputStream(pem), FilePasswordProvider.EMPTY);
                        if (keys == null || !keys.iterator().hasNext()) {
                            throw new SshTransport.AuthenticationException("credential is not a readable private key");
                        }
                        keys.forEach(session::addPublicKeyIdentity);
                    } catch (GeneralSecurityException e) {
                        throw new SshTransport.AuthenticationException("credential is not a readable private key");
                    } finally {
                        Arrays.fill(pem, (byte) 0);
                    }
                } else {
                    session.addPasswordIdentity(new String(value));
                }
            } finally {
                Arrays.fill(value, '\0');
            }
            try {
                session.auth().verify(timeout);
            } catch (IOException e) {
                throw new SshTransport.AuthenticationException("authentication or host key verification failed for " + username + "@" + host);
            }
            return new MinaTransport(session);
        } catch (IOException | RuntimeException e) {
            session.close(true);
            throw e;
        }
    }

    private record MinaTransport(ClientSession session) implements SshTransport {

        @Override
        public Exec exec(String command, byte[] stdin, Duration timeout) throws IOException {
            try (ChannelExec channel = session.createExecChannel(command)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                channel.setOut(out);
                channel.setErr(err);
                if (stdin != null) {
                    channel.setIn(new ByteArrayInputStream(stdin));
                }
                channel.open().verify(timeout);
                var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
                if (events.contains(ClientChannelEvent.TIMEOUT)) {
                    throw new IOException("remote command timed out");
                }
                Integer exit = channel.getExitStatus();
                return new Exec(exit == null ? -1 : exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void close() {
            session.close(false);
        }
    }
}
