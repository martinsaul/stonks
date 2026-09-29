package stonks.app.auth

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Request signing scheme `STONKS-V1` (see docs/API-AUTH.md).
 *
 * Every authenticated request carries a session id, a timestamp, a single-use nonce and
 * an ECDSA P-256 signature (IEEE P1363 r||s, as produced by WebCrypto) over:
 *
 * ```
 * STONKS-V1\n<METHOD>\n<path?query>\n<timestamp ms>\n<nonce>\n<hex sha256(body)>
 * ```
 */
object RequestSignature {
    const val SCHEME = "STONKS-V1"

    const val HEADER_SESSION = "X-Stonks-Session"
    const val HEADER_TIMESTAMP = "X-Stonks-Timestamp"
    const val HEADER_NONCE = "X-Stonks-Nonce"
    const val HEADER_SIGNATURE = "X-Stonks-Signature"
    const val HEADER_SERVER_TIME = "X-Stonks-Server-Time"

    private val b64url = Base64.getUrlDecoder()
    private val P256_ORDER = java.math.BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")

    fun canonical(method: String, pathAndQuery: String, timestamp: Long, nonce: String, body: ByteArray): String =
        "$SCHEME\n${method.uppercase()}\n$pathAndQuery\n$timestamp\n$nonce\n${sha256Hex(body)}"

    fun isValidNonce(nonce: String) = NONCE.matches(nonce)

    /** Parses a base64url SPKI DER public key and checks it is on P-256. */
    fun parsePublicKey(spkiBase64Url: String): PublicKey? = try {
        val key = publicKeyFromDer(b64url.decode(spkiBase64Url))
        if (key is ECPublicKey && key.params.order == P256_ORDER && key.params.curve.field.fieldSize == 256) key else null
    } catch (_: Exception) {
        null
    }

    fun publicKeyFromDer(der: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    fun verify(key: PublicKey, canonical: String, signatureBase64Url: String): Boolean = try {
        Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(key)
            update(canonical.toByteArray(Charsets.UTF_8))
            verify(b64url.decode(signatureBase64Url))
        }
    } catch (_: Exception) {
        false
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
