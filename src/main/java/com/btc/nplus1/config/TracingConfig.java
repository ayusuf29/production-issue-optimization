package com.btc.nplus1.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import java.time.Duration;
import java.util.Collections;

@Configuration
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    @Bean
    public OtlpHttpSpanExporter otlpHttpSpanExporter(
            @Value("${management.otlp.tracing.endpoint:http://localhost:4318/v1/traces}") String endpoint) {
        log.info(">>> Initializing OtlpHttpSpanExporter -> {}", endpoint);
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    public Resource otelResource(@Value("${spring.application.name:nplus1}") String applicationName) {
        return Resource.getDefault().merge(
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), applicationName))
        );
    }

    @Bean
    public SdkTracerProvider sdkTracerProvider(OtlpHttpSpanExporter spanExporter, Resource otelResource) {
        log.info(">>> Initializing SdkTracerProvider with BatchSpanProcessor");
        return SdkTracerProvider.builder()
                .setResource(otelResource)
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(
                        BatchSpanProcessor.builder(spanExporter)
                                .setScheduleDelay(Duration.ofMillis(200))
                                .build()
                )
                .build();
    }

    @Bean
    public ContextPropagators contextPropagators() {
        return ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    }

    @Bean
    public OpenTelemetry openTelemetry(SdkTracerProvider sdkTracerProvider, ContextPropagators contextPropagators) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(contextPropagators)
                .build();
    }

    @Bean
    public OtelCurrentTraceContext otelCurrentTraceContext() {
        return new OtelCurrentTraceContext();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry, OtelCurrentTraceContext otelCurrentTraceContext) {
        log.info(">>> Registering OtelTracer with Slf4JEventListener for MDC logging");
        io.opentelemetry.api.trace.Tracer otelApiTracer = openTelemetry.getTracer("nplus1");
        Slf4JEventListener slf4JEventListener = new Slf4JEventListener();
        return new OtelTracer(
                otelApiTracer,
                otelCurrentTraceContext,
                slf4JEventListener::onEvent,
                new OtelBaggageManager(otelCurrentTraceContext, Collections.emptyList(), Collections.emptyList())
        );
    }

    @Bean
    public OtelPropagator otelPropagator(ContextPropagators contextPropagators, OpenTelemetry openTelemetry) {
        return new OtelPropagator(contextPropagators, openTelemetry.getTracer("nplus1"));
    }

    @Bean
    public ObservationHandler<?> defaultTracingObservationHandler(Tracer tracer) {
        return new DefaultTracingObservationHandler(tracer);
    }

    @Bean
    public ObservationHandler<?> propagatingReceiverTracingObservationHandler(Tracer tracer, OtelPropagator otelPropagator) {
        return new PropagatingReceiverTracingObservationHandler<>(tracer, otelPropagator);
    }

    @Bean
    public ObservationHandler<?> propagatingSenderTracingObservationHandler(Tracer tracer, OtelPropagator otelPropagator) {
        return new PropagatingSenderTracingObservationHandler<>(tracer, otelPropagator);
    }

    /**
     * Enables @Observed annotation support on Spring beans/methods (e.g. OrderService, OrderRepository).
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Automatically captures HTTP Query Parameters (e.g. ?strategy=nplus1&limit=50)
     * and tags them onto the trace span in Tempo/Grafana.
     */
    @Bean
    public ObservationFilter queryParamsObservationFilter() {
        return context -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                String queryString = serverContext.getCarrier().getQueryString();
                if (queryString != null && !queryString.isBlank()) {
                    context.addHighCardinalityKeyValue(KeyValue.of("http.query_string", queryString));
                    // Also parse and add individual query params
                    for (String param : queryString.split("&")) {
                        String[] pair = param.split("=", 2);
                        if (pair.length == 2) {
                            context.addHighCardinalityKeyValue(KeyValue.of("query." + pair[0], pair[1]));
                        }
                    }
                }
            }
            return context;
        };
    }

    @Bean
    public CommandLineRunner diagnosticRunner(ApplicationContext ctx) {
        return args -> {
            log.info("================ OBSERVABILITY DIAGNOSTICS ================");
            log.info("Tracer bean present: {}", ctx.containsBean("tracer"));
            log.info("ObservationRegistry present: {}", ctx.containsBean("observationRegistry"));
            log.info("ObservedAspect (Method Tracing) present: {}", ctx.containsBean("observedAspect"));
            log.info("QueryParamsFilter present: {}", ctx.containsBean("queryParamsObservationFilter"));
            log.info("===========================================================");
        };
    }
}
