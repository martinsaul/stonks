package stonks.app.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(val error: String, val message: String)

/** An error with a stable machine-readable [code], rendered as [ErrorBody]. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message)

fun badRequest(message: String, code: String = "bad_request") = ApiException(HttpStatusCode.BadRequest, code, message)
fun notFound(message: String) = ApiException(HttpStatusCode.NotFound, "not_found", message)
fun forbidden(message: String = "Not allowed.") = ApiException(HttpStatusCode.Forbidden, "forbidden", message)

fun unauthorized(code: String, message: String, serverTimeMillis: Long) = ApiException(
    HttpStatusCode.Unauthorized, code, message,
    mapOf(stonks.app.auth.RequestSignature.HEADER_SERVER_TIME to serverTimeMillis.toString()),
)

fun tooManyRequests(retryAfterSeconds: Double, message: String = "Rate limit exceeded.") = ApiException(
    HttpStatusCode.TooManyRequests, "rate_limited", message,
    mapOf("Retry-After" to maxOf(1, kotlin.math.ceil(retryAfterSeconds).toInt()).toString()),
)
