package dev.secondbrain.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Initial relevance policy; the default needs later corpus evaluation. */
@ConfigurationProperties("secondbrain.search")
record SearchProperties(@DefaultValue("0.3") double minimumScore) {
    SearchProperties {
        if (!Double.isFinite(minimumScore) || minimumScore < -1 || minimumScore > 1)
            throw new IllegalArgumentException("Search minimum score must be between -1 and 1");
    }
}
