/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.common;

import org.jboss.logging.Logger;
import org.keycloak.common.Profile.Feature.Type;
import org.keycloak.common.profile.ProfileConfigResolver;
import org.keycloak.common.profile.ProfileConfigResolver.FeatureConfig;
import org.keycloak.common.profile.ProfileException;
import org.keycloak.common.util.KerberosJdkProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class Profile {

    private static volatile Map<String, TreeSet<Feature>> FEATURES;

    public enum Feature {
        AUTHORIZATION("Authorization Service", Type.DEFAULT),

        ACCOUNT_API("Account Management REST API", Type.DEFAULT),
        ACCOUNT2("Account Management Console 2", Type.DEFAULT, Feature.ACCOUNT_API),
        ACCOUNT3("Account Management Console 3", Type.PREVIEW, Feature.ACCOUNT_API),

        ADMIN_FINE_GRAINED_AUTHZ("Fine-Grained Admin Permissions", Type.PREVIEW),

        ADMIN_API("Admin API", Type.DEFAULT),

        ADMIN2("New Admin Console", Type.DEFAULT, Feature.ADMIN_API),

        DOCKER("Docker Registry protocol", Type.DISABLED_BY_DEFAULT),

        IMPERSONATION("Ability for admins to impersonate users", Type.DEFAULT),

        SCRIPTS("Write custom authenticators using JavaScript", Type.PREVIEW),

        TOKEN_EXCHANGE("Token Exchange Service", Type.PREVIEW),

        WEB_AUTHN("W3C Web Authentication (WebAuthn)", Type.DEFAULT),

        CLIENT_POLICIES("Client configuration policies", Type.DEFAULT),

        CIBA("OpenID Connect Client Initiated Backchannel Authentication (CIBA)", Type.DEFAULT),

        PAR("OAuth 2.0 Pushed Authorization Requests (PAR)", Type.DEFAULT),

        DYNAMIC_SCOPES("Dynamic OAuth 2.0 scopes", Type.EXPERIMENTAL),

        CLIENT_SECRET_ROTATION("Client Secret Rotation", Type.PREVIEW),

        STEP_UP_AUTHENTICATION("Step-up Authentication", Type.DEFAULT),

        // Check if kerberos is available in underlying JVM and auto-detect if feature should be enabled or disabled by default based on that
        KERBEROS("Kerberos", KerberosJdkProvider.getProvider().isKerberosAvailable() ? Type.DEFAULT : Type.DISABLED_BY_DEFAULT),

        RECOVERY_CODES("Recovery codes", Type.PREVIEW),

        UPDATE_EMAIL("Update Email Action", Type.PREVIEW),

        JS_ADAPTER("Host keycloak.js and keycloak-authz.js through the Keycloak server", Type.DEFAULT),

        FIPS("FIPS 140-2 mode", Type.DISABLED_BY_DEFAULT),

        DPOP("OAuth 2.0 Demonstrating Proof-of-Possession at the Application Layer", Type.PREVIEW),

        LINKEDIN_OAUTH("LinkedIn Social Identity Provider based on OAuth", Type.DEPRECATED),

        DEVICE_FLOW("OAuth 2.0 Device Authorization Grant", Type.DEFAULT),

        TRANSIENT_USERS("Transient users for brokering", Type.EXPERIMENTAL),

        MULTI_SITE("Multi-site support", Type.PREVIEW),
        ;

        private final Type type;
        private final String label;

        private Set<Feature> dependencies;
        private int version;

        Feature(String label, Type type, Feature... dependencies) {
            this(label, type, 1, dependencies);
        }

        /**
         * allowNameKey should be false for new versioned features to disallow using a legacy name, like account2
         */
        Feature(String label, Type type, int version, Feature... dependencies) {
            this.label = label;
            this.type = type;
            this.version = version;
            if (this.version > 1 && !this.name().endsWith("_V" + version)) {
                throw new IllegalStateException("It is expected that the enum name ends with the version");
            }
            this.dependencies = Arrays.stream(dependencies).collect(Collectors.toSet());
        }

        /**
         * Get the key that uniquely identifies this feature, may be used by users if
         * allowNameKey is true.
         * <p>
         * {@link #getVersionedKey()} should instead be shown to users where possible.
         */
        public String getKey() {
            return name().toLowerCase().replaceAll("_", "-");
        }

        /**
         * Return the key without any versioning.  All features of the same type
         * will share this key.
         */
        public String getUnversionedKey() {
            String key = getKey();
            if (version == 1) {
                return key;
            }
            return key.substring(0, key.length() - (String.valueOf(version).length() + 2));
        }

        /**
         * Return the key in the form key:v{version}
         */
        public String getVersionedKey() {
            return getUnversionedKey() + ":v" + version;
        }

        public String getLabel() {
            return label;
        }

        public Type getType() {
            return type;
        }

        public Set<Feature> getDependencies() {
            return dependencies;
        }

        public int getVersion() {
            return version;
        }

        public enum Type {
            // in priority order
            DEFAULT("Default"),
            DISABLED_BY_DEFAULT("Disabled by default"),
            DEPRECATED("Deprecated"),
            PREVIEW("Preview"),
            PREVIEW_DISABLED_BY_DEFAULT("Preview disabled by default"), // Preview features, which are not automatically enabled even with enabled preview profile (Needs to be enabled explicitly)
            EXPERIMENTAL("Experimental");

            private final String label;

            Type(String label) {
                this.label = label;
            }

            public String getLabel() {
                return label;
            }
        }
    }

    private static final Logger logger = Logger.getLogger(Profile.class);

    private static Profile CURRENT;

    private final ProfileName profileName;

    private final Map<Feature, Boolean> features;

    public static Profile defaults() {
        return configure();
    }

    public static Profile configure(ProfileConfigResolver... resolvers) {
        ProfileName profile = Arrays.stream(resolvers).map(ProfileConfigResolver::getProfileName).filter(Objects::nonNull).findFirst().orElse(ProfileName.DEFAULT);

        Map<Feature, Boolean> features = new LinkedHashMap<>();

        for (Map.Entry<String, TreeSet<Feature>> entry : getOrderedFeatures().entrySet()) {

            // first check by unversioned key - if enabled, choose the highest priority feature
            String unversionedFeature = entry.getKey();
            ProfileConfigResolver.FeatureConfig unversionedConfig = getFeatureConfig(unversionedFeature, resolvers);
            Feature enabledFeature = null;
            if (unversionedConfig == FeatureConfig.ENABLED) {
                enabledFeature = entry.getValue().iterator().next();
            }

            // now check each feature version to ensure consistency and select any features enabled by default
            boolean isExplicitlyEnabledFeature = false;
            for (Feature f : entry.getValue()) {
                ProfileConfigResolver.FeatureConfig configuration = getFeatureConfig(f.getVersionedKey(), resolvers);

                if (configuration != FeatureConfig.UNCONFIGURED && unversionedConfig != FeatureConfig.UNCONFIGURED) {
                    throw new ProfileException("Versioned feature " + f.getVersionedKey() + " is not expected as " + unversionedFeature + " is already " + unversionedConfig.name().toLowerCase());
                }

                switch (configuration) {
                case ENABLED:
                    if (isExplicitlyEnabledFeature) {
                        throw new ProfileException(
                                String.format("Multiple versions of the same feature %s, %s should not be enabled.",
                                        enabledFeature.getVersionedKey(), f.getVersionedKey()));
                    }
                    // even if something else was enabled by default, explicitly enabling a lower priority feature takes precedence
                    enabledFeature = f;
                    isExplicitlyEnabledFeature = true;
                    break;
                case DISABLED:
                    throw new ProfileException("Feature " + f.getVersionedKey() + " should not be disabled using a versioned key.");
                default:
                    if (unversionedConfig == FeatureConfig.UNCONFIGURED && enabledFeature == null && isEnabledByDefault(profile, f)) {
                        enabledFeature = f;
                    }
                    break;
                }
            }
            for (Feature f : entry.getValue()) {
                features.put(f, f == enabledFeature);
            }
        }

        verifyConfig(features);

        CURRENT = new Profile(profile, features);
        return CURRENT;
    }

    private static boolean isEnabledByDefault(ProfileName profile, Feature f) {
        switch (f.getType()) {
        case DEFAULT:
            return true;
        case PREVIEW:
            return profile.equals(ProfileName.PREVIEW);
        default:
            return false;
        }
    }

    private static ProfileConfigResolver.FeatureConfig getFeatureConfig(String unversionedFeature,
            ProfileConfigResolver... resolvers) {
        ProfileConfigResolver.FeatureConfig configuration = Arrays.stream(resolvers).map(r -> r.getFeatureConfig(unversionedFeature))
                .filter(r -> !r.equals(ProfileConfigResolver.FeatureConfig.UNCONFIGURED))
                .findFirst()
                .orElse(ProfileConfigResolver.FeatureConfig.UNCONFIGURED);
        return configuration;
    }

    /**
     * Compute a map of unversioned feature keys to ordered sets (highest first) of features.  The priority order for features is:
     * <p>
     * <ul>
     * <li>The highest default supported version
     * <li>The highest non-default supported version
     * <li>The highest deprecated version
     * <li>The highest preview version
     * <li>The highest experimental version
     * <ul>
     * <p>
     * Note the {@link Type} enum is ordered based upon priority.
     */
    private static Map<String, TreeSet<Feature>> getOrderedFeatures() {
        if (FEATURES == null) {
            // "natural" ordering low to high between two features
            Comparator<Feature> comparator = Comparator.comparing(Feature::getType).thenComparingInt(Feature::getVersion);
            // aggregate the features by unversioned key
            HashMap<String, TreeSet<Feature>> features = new HashMap<>();
            Stream.of(Feature.values()).forEach(f -> features.compute(f.getUnversionedKey(), (k, v) -> {
                if (v == null) {
                    v = new TreeSet<>(comparator.reversed()); // we want the highest priority first
                }
                v.add(f);
                return v;
            }));
            FEATURES = features;
        }
        return FEATURES;
    }

    public static Set<String> getAllUnversionedFeatureNames() {
        return Collections.unmodifiableSet(getOrderedFeatures().keySet());
    }

    /**
     * Get all of the feature versions for the given feature. They will be ordered by priority.
     * <p>
     * If the feature does not exist an empty collection will be returned.
     */
    public static Set<Feature> getFeatureVersions(String feature) {
        TreeSet<Feature> versions = getOrderedFeatures().get(feature);
        if (versions == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(versions);
    }

    public static Profile init(ProfileName profileName, Map<Feature, Boolean> features) {
        CURRENT = new Profile(profileName, features);
        return CURRENT;
    }

    private Profile(ProfileName profileName, Map<Feature, Boolean> features) {
        this.profileName = profileName;
        this.features = Collections.unmodifiableMap(features);

        logUnsupportedFeatures();
    }

    public static Profile getInstance() {
        return CURRENT;
    }

    public static boolean isFeatureEnabled(Feature feature) {
        return getInstance().features.get(feature);
    }

    public ProfileName getName() {
        return profileName;
    }

    public Set<Feature> getAllFeatures() {
        return features.keySet();
    }

    public Set<Feature> getDisabledFeatures() {
        return features.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /**
     * @return all features of type "preview" or "preview_disabled_by_default"
     */
    public Set<Feature> getPreviewFeatures() {
        return Stream.concat(getFeatures(Feature.Type.PREVIEW).stream(), getFeatures(Feature.Type.PREVIEW_DISABLED_BY_DEFAULT).stream())
                .collect(Collectors.toSet());
    }

    public Set<Feature> getExperimentalFeatures() {
        return getFeatures(Feature.Type.EXPERIMENTAL);
    }

    public Set<Feature> getDeprecatedFeatures() {
        return getFeatures(Feature.Type.DEPRECATED);
    }

    public Set<Feature> getFeatures(Feature.Type type) {
        return features.keySet().stream().filter(f -> f.getType().equals(type)).collect(Collectors.toSet());
    }

    public Map<Feature, Boolean> getFeatures() {
        return features;
    }

    public enum ProfileName {
        DEFAULT,
        PREVIEW
    }

    private static void verifyConfig(Map<Feature, Boolean> features) {
        for (Feature f : features.keySet()) {
            if (features.get(f) && f.getDependencies() != null) {
                for (Feature d : f.getDependencies()) {
                    if (!features.get(d)) {
                        throw new ProfileException("Feature " + f.getKey() + " depends on disabled feature " + d.getKey());
                    }
                }
            }
        }
    }

    private void logUnsupportedFeatures() {
        logUnsupportedFeatures(Feature.Type.PREVIEW, getPreviewFeatures(), Logger.Level.INFO);
        logUnsupportedFeatures(Feature.Type.EXPERIMENTAL, getExperimentalFeatures(), Logger.Level.WARN);
        logUnsupportedFeatures(Feature.Type.DEPRECATED, getDeprecatedFeatures(), Logger.Level.WARN);
    }

    private void logUnsupportedFeatures(Feature.Type type, Set<Feature> checkedFeatures, Logger.Level level) {
        Set<Feature.Type> checkedFeatureTypes = checkedFeatures.stream()
                .map(Feature::getType)
                .collect(Collectors.toSet());

        String enabledFeaturesOfType = features.entrySet().stream()
                .filter(e -> e.getValue() && checkedFeatureTypes.contains(e.getKey().getType()))
                .map(e -> e.getKey().getVersionedKey()).sorted().collect(Collectors.joining(", "));

        if (!enabledFeaturesOfType.isEmpty()) {
            logger.logv(level, "{0} features enabled: {1}", type.getLabel(), enabledFeaturesOfType);
        }
    }

}
