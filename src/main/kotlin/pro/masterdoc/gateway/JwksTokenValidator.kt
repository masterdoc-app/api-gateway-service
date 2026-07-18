package pro.masterdoc.gateway

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

class JwksTokenValidator(
    private val issuer: String,
    jwkSetUri: String,
) : TokenValidator {
    private val jwkProvider =
        JwkProviderBuilder(URI(jwkSetUri).toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(false)
            .build()

    override suspend fun validate(bearerToken: String): String? {
        return try {
            val decoded: DecodedJWT = JWT.decode(bearerToken)
            val jwk = jwkProvider.get(decoded.keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
            val verifier =
                JWT.require(algorithm)
                    .withIssuer(issuer)
                    .build()
            val verified = verifier.verify(bearerToken)
            verified.subject
        } catch (_: JWTVerificationException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
