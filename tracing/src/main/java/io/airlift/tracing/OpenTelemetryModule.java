package io.airlift.tracing;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.airlift.node.NodeInfo;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.incubating.DeploymentIncubatingAttributes;
import io.opentelemetry.semconv.incubating.HostIncubatingAttributes;
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes;
import io.opentelemetry.semconv.incubating.ProcessIncubatingAttributes;
import io.opentelemetry.semconv.incubating.ServiceIncubatingAttributes;

import java.util.Set;

import static com.google.common.base.StandardSystemProperty.JAVA_VM_NAME;
import static com.google.common.base.StandardSystemProperty.JAVA_VM_VENDOR;
import static com.google.common.base.StandardSystemProperty.JAVA_VM_VERSION;
import static com.google.common.base.StandardSystemProperty.OS_ARCH;
import static com.google.common.base.StandardSystemProperty.OS_NAME;
import static com.google.common.base.StandardSystemProperty.OS_VERSION;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.tracing.Tracing.noopTracer;
import static io.opentelemetry.sdk.trace.samplers.Sampler.parentBased;
import static io.opentelemetry.sdk.trace.samplers.Sampler.traceIdRatioBased;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class OpenTelemetryModule
        implements Module
{
    private static final String NODE_ANNOTATION_PREFIX = "io.airlift.node";
    private final String serviceName;
    private final String serviceVersion;

    public OpenTelemetryModule(String serviceName, String serviceVersion)
    {
        this.serviceName = requireNonNull(serviceName, "serviceName is null");
        this.serviceVersion = requireNonNull(serviceVersion, "serviceVersion is null");
    }

    @Override
    public void configure(Binder binder)
    {
        newSetBinder(binder, SpanProcessor.class);
        configBinder(binder).bindConfig(OpenTelemetryConfig.class);
    }

    @Provides
    @Singleton
    public OpenTelemetry createOpenTelemetry(Set<SpanProcessor> spanProcessors, SdkTracerProvider tracerProvider)
    {
        if (spanProcessors.isEmpty()) {
            return OpenTelemetry.noop();
        }

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Provides
    @Singleton
    public SdkTracerProvider createTracerProvider(NodeInfo nodeInfo, Set<SpanProcessor> spanProcessors, OpenTelemetryConfig config)
    {
        AttributesBuilder attributes = Attributes.builder()
                .put(ServiceAttributes.SERVICE_NAME, serviceName)
                .put(ServiceAttributes.SERVICE_VERSION, serviceVersion)
                .put(ServiceIncubatingAttributes.SERVICE_INSTANCE_ID, nodeInfo.getNodeId())
                .put(DeploymentIncubatingAttributes.DEPLOYMENT_ENVIRONMENT_NAME, nodeInfo.getEnvironment())
                .put(ProcessIncubatingAttributes.PROCESS_RUNTIME_NAME, System.getProperty("java.runtime.name"))
                .put(ProcessIncubatingAttributes.PROCESS_RUNTIME_VERSION, System.getProperty("java.runtime.version"))
                .put(ProcessIncubatingAttributes.PROCESS_RUNTIME_DESCRIPTION, processRuntime())
                .put(OsIncubatingAttributes.OS_TYPE, osType())
                .put(OsIncubatingAttributes.OS_NAME, OS_NAME.value())
                .put(OsIncubatingAttributes.OS_VERSION, OS_VERSION.value())
                .put(HostIncubatingAttributes.HOST_ARCH, hostArch());
        nodeInfo.getAnnotations().forEach((key, value) -> attributes.put(format("%s.%s", NODE_ANNOTATION_PREFIX, key), value));

        Resource resource = Resource.getDefault().merge(Resource.create(attributes.build()));

        return SdkTracerProvider.builder()
                .setSampler(parentBased(traceIdRatioBased(config.getSamplingRatio())))
                .addSpanProcessor(SpanProcessor.composite(spanProcessors))
                .setResource(resource)
                .build();
    }

    @Provides
    @Singleton
    public Tracer createTracer(Set<SpanProcessor> spanProcessors, SdkTracerProvider tracerProvider)
    {
        if (spanProcessors.isEmpty()) {
            return noopTracer();
        }
        return tracerProvider.get(serviceName);
    }

    private static String processRuntime()
    {
        String vendor = JAVA_VM_VENDOR.value();
        String name = JAVA_VM_NAME.value();
        String version = JAVA_VM_VERSION.value();
        if ((vendor == null) && (name == null) && (version == null)) {
            return null;
        }
        return "%s %s %s".formatted(vendor, name, version);
    }

    private static String osType()
    {
        return switch (nullToEmpty(OS_NAME.value())) {
            case "Linux" -> "linux";
            case "Mac OS X" -> "darwin";
            default -> null;
        };
    }

    private static String hostArch()
    {
        return switch (nullToEmpty(OS_ARCH.value())) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64" -> "arm64";
            case "ppc64le" -> "ppc64";
            default -> null;
        };
    }
}
