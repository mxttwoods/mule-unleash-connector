package com.work.unleash.internal;

import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_21;

import org.mule.sdk.api.annotation.Configurations;
import org.mule.sdk.api.annotation.Extension;
import org.mule.sdk.api.annotation.JavaVersionSupport;
import org.mule.sdk.api.annotation.dsl.xml.Xml;
import org.mule.sdk.api.annotation.error.ErrorTypes;

/**
 * Entry point of the Unleash connector.
 *
 * <p>Wraps the Unleash Java SDK so Mule flows can evaluate feature flags hosted on Unleash
 * OSS/Enterprise or on GitLab's Unleash-compatible feature flags API
 * ({@code /api/v4/feature_flags/unleash/<projectId>}).
 */
@Xml(prefix = "unleash")
@Extension(name = "Unleash")
@Configurations(UnleashConfiguration.class)
@JavaVersionSupport({JAVA_17, JAVA_21})
@ErrorTypes(UnleashErrorType.class)
public class UnleashExtension {
}
