/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.airlift.bootstrap;

import com.facebook.airlift.configuration.ConfigurationFactory;
import com.facebook.airlift.configuration.ConfigurationInspector;
import com.facebook.airlift.configuration.ConfigurationInspector.ConfigAttribute;
import com.facebook.airlift.configuration.ConfigurationInspector.ConfigRecord;
import com.facebook.airlift.configuration.ConfigurationModule;
import com.facebook.airlift.configuration.ValidationErrorModule;
import com.facebook.airlift.configuration.WarningsMonitor;
import com.facebook.airlift.log.Logger;
import com.facebook.airlift.log.Logging;
import com.facebook.airlift.log.LoggingConfiguration;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Stage;
import com.google.inject.spi.Message;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.facebook.airlift.configuration.ConfigurationLoader.getSystemProperties;
import static com.facebook.airlift.configuration.ConfigurationLoader.loadPropertiesFrom;
import static java.lang.Boolean.parseBoolean;

/**
 * Entry point for an application built using the platform codebase.
 * <p>
 * This class will:
 * <ul>
 * <li>load, validate and bind configurations</li>
 * <li>initialize logging</li>
 * <li>set up bootstrap management</li>
 * <li>create an Guice injector</li>
 * </ul>
 */
public class Bootstrap
{
    private final Logger log = Logger.get("Bootstrap");
    private final List<Module> modules;

    private Map<String, String> requiredConfigurationProperties;
    private Map<String, String> optionalConfigurationProperties;
    private boolean initializeLogging = true;
    private boolean quiet = getDefaultFromProperties("bootstrap.quiet", false);
    private boolean strictConfig = getDefaultFromProperties("bootstrap.strict-config", true);
    private boolean requireExplicitBindings = getDefaultFromProperties("bootstrap.require-explicit-bindings", true);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{ENV:([a-zA-Z][a-zA-Z0-9_]*)}");

    private boolean initialized;

    public Bootstrap(Module... modules)
    {
        this(ImmutableList.copyOf(modules));
    }

    public Bootstrap(Iterable<? extends Module> modules)
    {
        this.modules = ImmutableList.copyOf(modules);
    }

    @Beta
    public Bootstrap setRequiredConfigurationProperty(String key, String value)
    {
        if (this.requiredConfigurationProperties == null) {
            this.requiredConfigurationProperties = new TreeMap<>();
        }
        this.requiredConfigurationProperties.put(key, value);
        return this;
    }

    @Beta
    public Bootstrap setRequiredConfigurationProperties(Map<String, String> requiredConfigurationProperties)
    {
        if (this.requiredConfigurationProperties == null) {
            this.requiredConfigurationProperties = new TreeMap<>();
        }
        this.requiredConfigurationProperties.putAll(requiredConfigurationProperties);
        return this;
    }

    @Beta
    public Bootstrap setOptionalConfigurationProperty(String key, String value)
    {
        if (this.optionalConfigurationProperties == null) {
            this.optionalConfigurationProperties = new TreeMap<>();
        }
        this.optionalConfigurationProperties.put(key, value);
        return this;
    }

    @Beta
    public Bootstrap setOptionalConfigurationProperties(Map<String, String> optionalConfigurationProperties)
    {
        if (this.optionalConfigurationProperties == null) {
            this.optionalConfigurationProperties = new TreeMap<>();
        }
        this.optionalConfigurationProperties.putAll(optionalConfigurationProperties);
        return this;
    }

    @Beta
    public Bootstrap doNotInitializeLogging()
    {
        this.initializeLogging = false;
        return this;
    }

    public Bootstrap quiet()
    {
        this.quiet = true;
        return this;
    }

    /**
     * Disable the requirement that required configuration property must be used
     * in some Config classes. This could be useful for testing purpose when
     * deploying an old Presto version with the latest set of config properties.
     */
    public Bootstrap noStrictConfig()
    {
        this.strictConfig = false;
        return this;
    }

    @SuppressWarnings("unused")
    public Bootstrap requireExplicitBindings(boolean requireExplicitBindings)
    {
        this.requireExplicitBindings = requireExplicitBindings;
        return this;
    }

    public Injector initialize()
    {
        Preconditions.checkState(!initialized, "Already initialized");
        initialized = true;

        Logging logging = null;
        if (initializeLogging) {
            logging = Logging.initialize();
        }

        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> log.error(throwable, "Uncaught exception in thread %s", thread.getName()));

        Map<String, String> requiredProperties;
        ConfigurationFactory configurationFactory;
        if (requiredConfigurationProperties == null) {
            // initialize configuration
            log.info("Loading configuration");

            requiredProperties = Collections.emptyMap();
            String configFile = System.getProperty("config");
            if (configFile != null) {
                try {
                    requiredProperties = loadPropertiesFrom(configFile);
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        else {
            requiredProperties = requiredConfigurationProperties;
        }
        SortedMap<String, String> properties = new TreeMap<>();
        SortedMap<String, String> environmentErrors = new TreeMap<>();
        if (optionalConfigurationProperties != null) {
            properties.putAll(optionalConfigurationProperties);
        }
        properties.putAll(requiredProperties);
        properties.putAll(getSystemProperties());
        properties = replaceWithEnvironmentVariables(properties, System.getenv(), environmentErrors::put);
        properties = ImmutableSortedMap.copyOf(properties);

        configurationFactory = new ConfigurationFactory(properties, log::warn);

        if (logging != null) {
            // initialize logging
            log.info("Initializing logging");
            LoggingConfiguration configuration = configurationFactory.build(LoggingConfiguration.class);
            logging.configure(configuration);
        }

        // Register configuration classes defined in the modules
        configurationFactory.registerConfigurationClasses(modules);

        // Validate configuration classes
        List<Message> messages = configurationFactory.validateRegisteredConfigurationProvider();

        // at this point all config file properties should be used
        // so we can calculate the unused properties
        Map<String, String> unusedProperties = new TreeMap<>(requiredProperties);
        unusedProperties.keySet().removeAll(configurationFactory.getUsedProperties());

        // Log effective configuration
        if (!quiet) {
            logConfiguration(configurationFactory, unusedProperties);
        }

        // system modules
        Builder<Module> moduleList = ImmutableList.builder();
        moduleList.add(new LifeCycleModule());
        moduleList.add(new ConfigurationModule(configurationFactory));
        if (!messages.isEmpty()) {
            moduleList.add(new ValidationErrorModule(messages));
        }
        moduleList.add(binder -> binder.bind(WarningsMonitor.class).toInstance(log::warn));

        // disable broken Guice "features"
        moduleList.add(Binder::disableCircularProxies);
        if (requireExplicitBindings) {
            moduleList.add(Binder::requireExplicitBindings);
        }

        // todo this should be part of the ValidationErrorModule
        if (strictConfig) {
            moduleList.add(binder -> {
                for (Entry<String, String> unusedProperty : unusedProperties.entrySet()) {
                    binder.addError("Configuration property '%s' was not used", unusedProperty.getKey());
                }

                for (Entry<String, String> environmentError : environmentErrors.entrySet()) {
                    binder.addError(environmentError.getValue());
                }
            });
        }
        moduleList.addAll(modules);

        // create the injector
        Injector injector = Guice.createInjector(Stage.PRODUCTION, moduleList.build());

        // Create the life-cycle manager
        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);

        // Start services
        if (lifeCycleManager.size() > 0) {
            lifeCycleManager.start();
        }

        return injector;
    }

    private void logConfiguration(ConfigurationFactory configurationFactory, Map<String, String> unusedProperties)
    {
        ColumnPrinter columnPrinter = makePrinterForConfiguration(configurationFactory);

        try (PrintWriter out = new PrintWriter(new LoggingWriter(log))) {
            columnPrinter.print(out);
        }

        // Warn about unused properties
        if (!unusedProperties.isEmpty()) {
            log.warn("UNUSED PROPERTIES");
            for (String unusedProperty : unusedProperties.keySet()) {
                log.warn("%s", unusedProperty);
            }
            log.warn("");
        }
    }

    private static ColumnPrinter makePrinterForConfiguration(ConfigurationFactory configurationFactory)
    {
        ConfigurationInspector configurationInspector = new ConfigurationInspector();

        ColumnPrinter columnPrinter = new ColumnPrinter(
                "PROPERTY", "DEFAULT", "RUNTIME", "DESCRIPTION");

        for (ConfigRecord<?> record : configurationInspector.inspect(configurationFactory)) {
            for (ConfigAttribute attribute : record.getAttributes()) {
                columnPrinter.addValues(
                        attribute.getPropertyName(),
                        attribute.getDefaultValue(),
                        attribute.getCurrentValue(),
                        attribute.getDescription());
            }
        }
        return columnPrinter;
    }

    private boolean getDefaultFromProperties(String propertyName, boolean defaultValue)
    {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null) {
            return parseBoolean(propertyValue);
        }
        return defaultValue;
    }

    @VisibleForTesting
    static SortedMap<String, String> replaceWithEnvironmentVariables(
            Map<String, String> properties,
            Map<String, String> environment,
            BiConsumer<String, String> onError)
    {
        SortedMap<String, String> results = new TreeMap<>();
        properties.forEach((key, value) -> {
            // TODO: Replace with Matcher.replaceAll(Function) or replace StringBuffer with StringBuilder after upgrade to Java 9+
            StringBuffer result = new StringBuffer();
            ImmutableSet.Builder<String> undefinedBuilder = ImmutableSet.builderWithExpectedSize(0);
            Matcher matcher = ENV_PATTERN.matcher(value);
            while (matcher.find()) {
                String variable = matcher.group(1);
                String variableValue = environment.get(variable);
                if (variableValue == null) {
                    undefinedBuilder.add(variable);
                }
                else {
                    matcher.appendReplacement(result, variableValue);
                }
            }
            ImmutableSet<String> undefined = undefinedBuilder.build();
            matcher.appendTail(result);
            if (undefined.isEmpty()) {
                results.put(key, result.toString());
            }
            else {
                onError.accept(key, String.format("Configuration property `%s` references undefined environment variable(s): %s", key, undefined));
            }
        });
        return results;
    }
}
