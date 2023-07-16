package com.sksamuel.signum.dynamodb

import io.kotest.common.concurrentHashMap
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import software.amazon.awssdk.core.ClientType
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttribute
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

class DynamodbMetrics : MeterBinder, ExecutionInterceptor {

   companion object {
      val requestIdAttribute = ExecutionAttribute<String>("RequestId")
      val startTimeAttribute = ExecutionAttribute<Long>("StartTime")
   }

   private fun timer(opname: String, clientType: ClientType, success: Boolean) = Timer
      .builder("signum.dynamodb.requests.timer")
      .tag("operation", opname)
      .tag("client_type", clientType.name)
      .tag("success", success.toString())
      .description("Timer for operations")
      .register(registry)

   private val gauges = concurrentHashMap<Pair<String, ClientType>, AtomicLong>()

   private fun requestSize(opname: String, clientType: ClientType): AtomicLong {
      return gauges.getOrPut(Pair(opname, clientType)) {
         val number = AtomicLong()
         Gauge.builder("signum.dynamodb.requests.size") { number }
            .tag("operation", opname)
            .tag("client_type", clientType.name)
            .description("Request size gauge")
            .register(registry)
         number
      }
   }

   private var registry: MeterRegistry = SimpleMeterRegistry()

   override fun bindTo(registry: MeterRegistry) {
      this.registry = registry
   }

   override fun beforeExecution(context: Context.BeforeExecution, executionAttributes: ExecutionAttributes) {
      executionAttributes.putAttribute(requestIdAttribute, UUID.randomUUID().toString())
      executionAttributes.putAttribute(startTimeAttribute, System.currentTimeMillis())
   }

   override fun beforeTransmission(context: Context.BeforeTransmission, executionAttributes: ExecutionAttributes) {
      val requestSize = context.requestBody().flatMap { it.optionalContentLength() }.getOrNull()
      if (requestSize != null) {
         val opname = executionAttributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME)
         val clientType = executionAttributes.getAttribute(SdkExecutionAttribute.CLIENT_TYPE)
         requestSize(opname, clientType).set(requestSize)
      }
   }

   override fun afterExecution(context: Context.AfterExecution, executionAttributes: ExecutionAttributes) {
      val success = context.httpResponse().isSuccessful
      val opname = executionAttributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME)
      val clientType = executionAttributes.getAttribute(SdkExecutionAttribute.CLIENT_TYPE)
      val time = System.currentTimeMillis() - executionAttributes.getAttribute(startTimeAttribute)
      timer(opname, clientType, success).record(time.milliseconds.toJavaDuration())
   }
}
