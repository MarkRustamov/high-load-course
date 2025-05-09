package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.*
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val timeout = requestAverageProcessingTime.toMillis() * 2

//    private val rateLimiter = FixedWindowRateLimiter(rateLimitPerSec, 1, TimeUnit.SECONDS)
    private val rateLimiter = SlidingWindowRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1))
//    private val rateLimiter = TokenBucketRateLimiter(rateLimitPerSec, rateLimitPerSec + 2, 1, TimeUnit.SECONDS)
//    private val rateLimiter = LeakingBucketRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1), rateLimitPerSec)
    private val window = OngoingWindow(parallelRequests)
//    private val executorService = Executors.newFixedThreadPool(parallelRequests)

//    private val client = OkHttpClient.Builder().callTimeout(Duration.ofMillis(timeout)).build()
//    private val maxAttemptsCount = 3

    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        // Ограничиваем количество запросов к сервису
        while (!(rateLimiter.tick() && window.tryAcquire())) {
            Thread.sleep(10)
        }

        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()
        logger.info("[$accountName] Submit for $paymentId , txId: $transactionId")

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("http://localhost:1234/external/process?serviceName=${serviceName}&accountName=${accountName}&transactionId=$transactionId&paymentId=$paymentId&amount=$amount&timeout=${Duration.ofMillis(timeout)}"))
            .version(HttpClient.Version.HTTP_2)
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofMillis(timeout))
            .build()

        try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAcceptAsync { response ->
                    val body = try {
                        mapper.readValue(response.body(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.statusCode()}, reason: ${response.body()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                    // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                    // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }
                }
                .orTimeout(timeout, TimeUnit.MILLISECONDS)
        }
        catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                    }
                }

                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message)
                    }
                }
            }
        }
        finally {
            window.release()
        }
    }

//    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
//        executorService.submit {
//            // Ограничиваем количество запросов к сервису
//            while (!(rateLimiter.tick() && window.tryAcquire())) {
//                Thread.sleep(10)
//            }
//
//            logger.warn("[$accountName] Submitting payment request for payment $paymentId")
//
//            val transactionId = UUID.randomUUID()
//            logger.info("[$accountName] Submit for $paymentId , txId: $transactionId")
//
//            // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
//            // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
//            paymentESService.update(paymentId) {
//                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
//            }
//
//            val request = Request.Builder().run {
//                url(
//                    "http://localhost:1234/external/process?serviceName=${serviceName}&accountName=${accountName}&transactionId=$transactionId&paymentId=$paymentId&amount=$amount&timeout=${
//                        Duration.ofMillis(
//                            timeout
//                        )
//                    }"
//                )
//                post(emptyBody)
//            }.build()
//
//            var currentAttempt = 1
//            var needRetry = true
//            while (needRetry && currentAttempt <= maxAttemptsCount) {
//                try {
//                    client.newCall(request).execute().use { response ->
//                        val body = try {
//                            mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
//                        } catch (e: Exception) {
//                            logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
//                            ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
//                        }
//
//                        logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")
//
//                        // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
//                        // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
//                        paymentESService.update(paymentId) {
//                            it.logProcessing(body.result, now(), transactionId, reason = body.message)
//                        }
//
//                        if (body.result) {
//                            needRetry = false
//                        }
//                    }
//                } catch (e: Exception) {
//                    when (e) {
//                        is SocketTimeoutException -> {
//                            logger.error(
//                                "[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId",
//                                e
//                            )
//                            paymentESService.update(paymentId) {
//                                it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
//                            }
//                        }
//
//                        else -> {
//                            logger.error(
//                                "[$accountName] Payment failed for txId: $transactionId, payment: $paymentId",
//                                e
//                            )
//
//                            paymentESService.update(paymentId) {
//                                it.logProcessing(false, now(), transactionId, reason = e.message)
//                            }
//                        }
//                    }
//                }
//                currentAttempt++
//            }
//            window.release()
//        }
//    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName
}

public fun now() = System.currentTimeMillis()