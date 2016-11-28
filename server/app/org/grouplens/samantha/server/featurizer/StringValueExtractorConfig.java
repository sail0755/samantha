package org.grouplens.samantha.server.featurizer;

import org.grouplens.samantha.modeler.featurizer.FeatureExtractor;
import org.grouplens.samantha.modeler.featurizer.StringValueExtractor;
import org.grouplens.samantha.server.io.RequestContext;
import play.Configuration;
import play.inject.Injector;

public class StringValueExtractorConfig implements FeatureExtractorConfig {
    private final String indexName;
    private final String feaName;
    private final String attrName;

    private StringValueExtractorConfig(String indexName,
                                       String attrName,
                                       String feaName) {
        this.indexName = indexName;
        this.attrName = attrName;
        this.feaName = feaName;
    }

    public FeatureExtractor getFeatureExtractor(RequestContext requestContext) {
        return new StringValueExtractor(indexName, attrName, feaName);
    }

    public static FeatureExtractorConfig
    getFeatureExtractorConfig(Configuration extractorConfig,
                              Injector injector) {
        return new StringValueExtractorConfig(
                extractorConfig.getString("indexName"),
                extractorConfig.getString("attrName"),
                extractorConfig.getString("feaName")
        );
    }
}