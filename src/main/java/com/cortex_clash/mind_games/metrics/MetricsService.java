package com.cortex_clash.mind_games.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MetricsService {
    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordCounter(MetricName metricName, Map<TagName, TagValue> tags) {
        if (tags == null) {
            tags = Map.of();
        }
        meterRegistry.counter(
                metricName.metricName(),
                tags.entrySet().stream()
                        .map(entry -> Tag.of(entry.getKey().key(), entry.getValue().value()))
                        .toList()
        ).increment();
    }
}
