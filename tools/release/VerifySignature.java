import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Verifies public signing evidence only; never opens a keystore or handles a password. */
public final class VerifySignature {
    private static final int[] PLATFORM_LEVELS = {26, 27, 28, 32, 33, 35};

    private VerifySignature() {}

    public static void main(String[] args) {
        try {
            require(args.length == 4 && ("apk".equals(args[0]) || "lineage".equals(args[0])),
                    "Usage: VerifySignature apk|lineage <file> <oldSha256> <newSha256>");
            File input = new File(args[1]);
            require(Files.isRegularFile(input.toPath()), "Input must be an existing regular file");
            String oldDigest = normalizeDigest(args[2]);
            String newDigest = normalizeDigest(args[3]);
            require(!oldDigest.equals(newDigest), "Rotation must use distinct certificates");
            String evidence = "apk".equals(args[0])
                    ? verifyApk(input, oldDigest, newDigest)
                    : verifyLineage(SigningCertificateLineage.readFromFile(input), oldDigest, newDigest);
            System.out.println("{\"status\":\"verified\",\"mode\":" + quote(args[0])
                    + ",\"evidence\":" + evidence + "}");
        } catch (Exception failure) {
            System.out.println("{\"status\":\"failed\",\"errorType\":"
                    + quote(failure.getClass().getSimpleName()) + ",\"error\":"
                    + quote(String.valueOf(failure.getMessage())) + "}");
            System.exit(1);
        }
    }

    private static String verifyApk(File apk, String oldDigest, String newDigest) throws Exception {
        // This independently parses and cryptographically validates the embedded proof of rotation.
        String embeddedLineage = verifyLineage(
                SigningCertificateLineage.readFromApkFile(apk), oldDigest, newDigest);
        List<String> platforms = new ArrayList<>();
        for (int sdk : PLATFORM_LEVELS) {
            ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                    .setMinCheckedPlatformVersion(sdk)
                    .setMaxCheckedPlatformVersion(sdk)
                    .build().verify();
            require(result.isVerified() && !result.containsErrors(),
                    "SDK " + sdk + " signature verification failed: " + result.getAllErrors());
            List<X509Certificate> effectiveSigners = result.getSignerCertificates();
            require(effectiveSigners.size() == 1,
                    "SDK " + sdk + " must have exactly one effective signer");
            String effectiveDigest = digest(effectiveSigners.get(0));
            require(effectiveDigest.equals(sdk < 28 ? oldDigest : newDigest),
                    "SDK " + sdk + " effective signer does not match the expected certificate");
            String supportedLineage = "null";
            if (sdk < 28) {
                require(result.isVerifiedUsingV2Scheme() && !result.isVerifiedUsingV3Scheme()
                                && !result.isVerifiedUsingV31Scheme(),
                        "SDK " + sdk + " must verify using the legacy v2 signature");
            } else {
                require(result.isVerifiedUsingV3Scheme(),
                        "SDK " + sdk + " must verify the rotated v3 signature");
                supportedLineage = verifyLineage(
                        result.getSigningCertificateLineage(), oldDigest, newDigest);
            }
            platforms.add("{\"sdk\":" + sdk + ",\"verified\":true,\"effectiveSignerSha256\":"
                    + quote(effectiveDigest) + ",\"v1\":" + result.isVerifiedUsingV1Scheme()
                    + ",\"v2\":" + result.isVerifiedUsingV2Scheme()
                    + ",\"v3\":" + result.isVerifiedUsingV3Scheme()
                    + ",\"v31\":" + result.isVerifiedUsingV31Scheme()
                    + ",\"warnings\":" + strings(result.getWarnings())
                    + ",\"lineage\":" + supportedLineage + "}");
        }
        return "{\"rotationMinSdk\":28,\"embeddedLineage\":" + embeddedLineage
                + ",\"platforms\":[" + String.join(",", platforms) + "]}";
    }

    private static String verifyLineage(SigningCertificateLineage lineage,
            String oldDigest, String newDigest) throws Exception {
        require(lineage != null, "Required signing certificate lineage is absent");
        List<X509Certificate> certificates = lineage.getCertificatesInLineage();
        require(lineage.size() == 2 && certificates.size() == 2,
                "Expected exactly two certificates in the rotation lineage");
        require(digest(certificates.get(0)).equals(oldDigest)
                        && digest(certificates.get(1)).equals(newDigest)
                        && lineage.isCertificateLatestInLineage(certificates.get(1)),
                "Lineage must be ordered old certificate then new certificate");
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < certificates.size(); index++) {
            X509Certificate certificate = certificates.get(index);
            SigningCertificateLineage.SignerCapabilities capabilities =
                    lineage.getSignerCapabilities(certificate);
            if (index == 0) {
                require(capabilities.hasInstalledData(),
                        "Predecessor must retain installed-data capability for upgrades");
                require(!capabilities.hasRollback(),
                        "Predecessor rollback capability must be disabled");
            }
            entries.add("{\"sha256\":" + quote(digest(certificate))
                    + ",\"installedData\":" + capabilities.hasInstalledData()
                    + ",\"sharedUid\":" + capabilities.hasSharedUid()
                    + ",\"permission\":" + capabilities.hasPermission()
                    + ",\"rollback\":" + capabilities.hasRollback()
                    + ",\"auth\":" + capabilities.hasAuth() + "}");
        }
        return "{\"certificates\":[" + String.join(",", entries) + "]}";
    }

    private static String digest(X509Certificate certificate) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static String normalizeDigest(String value) {
        String normalized = value.replace(":", "").toLowerCase(Locale.ROOT);
        require(normalized.matches("[0-9a-f]{64}"), "Expected a SHA-256 certificate digest");
        return normalized;
    }

    private static String strings(List<?> values) {
        List<String> escaped = new ArrayList<>();
        for (Object value : values) escaped.add(quote(String.valueOf(value)));
        return "[" + String.join(",", escaped) + "]";
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 32) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
