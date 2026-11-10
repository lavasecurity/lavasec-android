import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.JarFile;

/** Verify every AAB payload entry against the pinned upload certificate, without executing it. */
class VerifyPlaySignature {
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[1].matches("[0-9a-f]{64}")) throw new Exception();
            var metadata = Set.of("META-INF/MANIFEST.MF", "META-INF/LAVASEC.SF", "META-INF/LAVASEC.RSA");
            int verified = 0;
            try (var jar = new JarFile(Path.of(args[0]).toFile(), true)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    // Reading all bytes triggers JarFile's cryptographic signature and digest checks.
                    try (var stream = jar.getInputStream(entry)) {
                        stream.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    if (metadata.contains(entry.getName())) continue;
                    CodeSigner[] signers = entry.getCodeSigners();
                    if (signers == null || signers.length != 1) throw new Exception();
                    var certificate = (X509Certificate) signers[0].getSignerCertPath().getCertificates().get(0);
                    certificate.checkValidity();
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                    if (!digest.equals(args[1])) throw new Exception();
                    verified++;
                }
            }
            if (verified == 0) throw new Exception();
        } catch (Exception error) {
            System.exit(1);
        }
    }
}
