package io.github.sparkcyf.voicesipagent.privileged.bootstrap;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** One persistent identity, confined to private no-backup storage and loopback. */
final class LocalAdb extends AbsAdbConnectionManager {
    private final PrivateKey key;
    private final Certificate certificate;
    LocalAdb(Context context) throws Exception {
        AtomicFile identity = new AtomicFile(new File(context.getNoBackupFilesDir(), "yx-adb-identity.bin"));
        if (!identity.getBaseFile().exists()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            java.security.KeyPair pair = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=QQ-SIP Local Bootstrap");
            byte[] cert = new JcaX509v3CertificateBuilder(subject,
                new BigInteger(128, new SecureRandom()), new Date(System.currentTimeMillis() - 60000),
                new Date(System.currentTimeMillis() + 365L * 86400000), subject, pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())).getEncoded();
            FileOutputStream out = identity.startWrite();
            try {
                DataOutputStream data = new DataOutputStream(out);
                byte[] encoded = pair.getPrivate().getEncoded();
                data.writeInt(encoded.length); data.write(encoded);
                data.writeInt(cert.length); data.write(cert); data.flush();
                identity.finishWrite(out);
            } catch (Exception e) { identity.failWrite(out); throw e; }
        }
        try (DataInputStream in = new DataInputStream(identity.openRead())) {
            byte[] encodedKey = readBounded(in);
            byte[] encodedCert = readBounded(in);
            if (in.read() != -1) throw new IOException("Unexpected identity data");
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encodedCert));
        }
        setApi(Build.VERSION.SDK_INT);
        setHostAddress("127.0.0.1");
        setTimeout(10, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
    }
    private static byte[] readBounded(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size <= 0 || size > 16384) throw new IOException("Invalid identity size");
        byte[] bytes = new byte[size]; input.readFully(bytes); return bytes;
    }
    @Override protected PrivateKey getPrivateKey() { return key; }
    @Override protected Certificate getCertificate() { return certificate; }
    @Override protected String getDeviceName() { return "QQ-SIP-Call-Service"; }
}
