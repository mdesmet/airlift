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
package io.airlift.configuration;

import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.reflect.TypeToken;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.inject.Binding;
import com.google.inject.ConfigurationException;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.spi.DefaultElementVisitor;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.spi.InstanceBinding;
import com.google.inject.spi.Message;
import com.google.inject.spi.ProviderInstanceBinding;
import io.airlift.configuration.ConfigurationMetadata.AttributeMetadata;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.newConcurrentHashSet;
import static io.airlift.configuration.Problems.exceptionFor;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

public class ConfigurationFactory
{
    @GuardedBy("VALIDATOR")
    private static final Validator VALIDATOR;

    private static final Splitter VALUE_SPLITTER = Splitter.on(",").omitEmptyStrings().trimResults();
    private static final LoadingCache<Class<?>, ConfigurationMetadata<?>> METADATA_CACHE = CacheBuilder.newBuilder()
            .build(CacheLoader.from(ConfigurationMetadata::getConfigurationMetadata));

    static {
        ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // this prevents hibernate validator from using the thread context classloader
            Thread.currentThread().setContextClassLoader(null);
            VALIDATOR = Validation.byProvider(HibernateValidator.class)
                    .configure()
                    .externalClassLoader(HibernateValidator.class.getClassLoader())
                    .ignoreXmlConfiguration()
                    .messageInterpolator(new ParameterMessageInterpolator(Set.of(ENGLISH), ENGLISH, false))
                    .buildValidatorFactory()
                    .getValidator();
        }
        finally {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
        }
    }

    private final Map<String, String> properties;
    private final WarningsMonitor warningsMonitor;
    private final ConcurrentMap<ConfigurationProvider<?>, Object> instanceCache = new ConcurrentHashMap<>();
    private final Set<ConfigPropertyMetadata> usedProperties = newConcurrentHashSet();
    private final Set<String> allSeenProperties = new HashSet<>();
    private final Set<ConfigurationProvider<?>> registeredProviders = newConcurrentHashSet();
    @GuardedBy("this")
    private final List<Consumer<ConfigurationProvider<?>>> configurationBindingListeners = new ArrayList<>();
    private final ListMultimap<Key<?>, ConfigDefaultsHolder<?>> registeredDefaultConfigs = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());

    public ConfigurationFactory(Map<String, String> properties)
    {
        this(properties, null);
    }

    public ConfigurationFactory(Map<String, String> properties, WarningsMonitor warningsMonitor)
    {
        this.properties = ImmutableMap.copyOf(properties);
        this.warningsMonitor = warningsMonitor;
    }

    public Map<String, String> getProperties()
    {
        return properties;
    }

    /**
     * Marks the specified property as consumed.
     */
    public void consumeProperty(ConfigPropertyMetadata property)
    {
        requireNonNull(property, "property is null");
        usedProperties.add(property);
    }

    public Set<ConfigPropertyMetadata> getUsedProperties()
    {
        return ImmutableSortedSet.copyOf(usedProperties);
    }

    /**
     * Registers all configuration classes in the module, so they can be part
     * of configuration inspection.
     *
     * @return A collection of Guice errors encountered
     */
    public Collection<Message> registerConfigurationClasses(Module module)
    {
        return registerConfigurationClasses(ImmutableList.of(module));
    }

    /**
     * Returns names of all configuration properties that were seen during the configuration
     */
    public Set<String> getAllSeenProperties()
    {
        return allSeenProperties;
    }

    /**
     * Registers all configuration classes in the modules, so they can be part
     * of configuration inspection.
     *
     * @return A collection of Guice errors encountered
     */
    @SuppressWarnings("deprecation")
    public Collection<Message> registerConfigurationClasses(Collection<? extends Module> modules)
    {
        // some modules need access to configuration factory so they can lazy register additional config classes
        // initialize configuration factory
        modules.stream()
                .filter(ConfigurationAwareModule.class::isInstance)
                .map(ConfigurationAwareModule.class::cast)
                .forEach(module -> module.setConfigurationFactory(this));

        List<Message> errors = new ArrayList<>();

        for (Element element : Elements.getElements(modules)) {
            element.acceptVisitor(new DefaultElementVisitor<Void>()
            {
                @Override
                public <T> Void visit(Binding<T> binding)
                {
                    if (binding instanceof InstanceBinding) {
                        InstanceBinding<T> instanceBinding = (InstanceBinding<T>) binding;

                        // configuration listener
                        if (instanceBinding.getInstance() instanceof ConfigurationBindingListenerHolder) {
                            addConfigurationBindingListener(((ConfigurationBindingListenerHolder) instanceBinding.getInstance()).getConfigurationBindingListener());
                        }

                        // config defaults
                        if (instanceBinding.getInstance() instanceof ConfigDefaultsHolder) {
                            registerConfigDefaults((ConfigDefaultsHolder<?>) instanceBinding.getInstance());
                        }
                    }

                    // configuration provider
                    if (binding instanceof ProviderInstanceBinding) {
                        ProviderInstanceBinding<?> providerInstanceBinding = (ProviderInstanceBinding<?>) binding;
                        Provider<?> provider = providerInstanceBinding.getProviderInstance();
                        if (provider instanceof ConfigurationProvider) {
                            registerConfigurationProvider((ConfigurationProvider<?>) provider, Optional.of(binding.getSource()));
                        }
                    }
                    return null;
                }

                @Override
                public Void visit(Message error)
                {
                    errors.add(error);
                    return null;
                }
            });
        }

        return errors;
    }

    void registerConfigurationProvider(ConfigurationProvider<?> configurationProvider, Optional<Object> bindingSource)
    {
        configurationProvider.setConfigurationFactory(this);
        configurationProvider.setBindingSource(bindingSource);

        ImmutableList<Consumer<ConfigurationProvider<?>>> listeners = ImmutableList.of();
        synchronized (this) {
            if (registeredProviders.add(configurationProvider)) {
                listeners = ImmutableList.copyOf(configurationBindingListeners);
            }
        }
        listeners.forEach(listener -> listener.accept(configurationProvider));
    }

    public void addConfigurationBindingListener(ConfigurationBindingListener listener)
    {
        ConfigurationProviderConsumer consumer = new ConfigurationProviderConsumer(listener);

        ImmutableSet<ConfigurationProvider<?>> currentProviders;
        synchronized (this) {
            configurationBindingListeners.add(consumer);
            currentProviders = ImmutableSet.copyOf(registeredProviders);
        }
        currentProviders.forEach(consumer);
    }

    public List<Message> validateRegisteredConfigurationProvider()
    {
        List<Message> messages = new ArrayList<>();
        for (ConfigurationProvider<?> configurationProvider : ImmutableList.copyOf(registeredProviders)) {
            try {
                // call the getter which will cause object creation
                configurationProvider.get();
            }
            catch (ConfigurationException e) {
                // if we got errors, add them to the errors list
                ImmutableList<Object> sources = configurationProvider.getBindingSource().map(ImmutableList::of).orElse(ImmutableList.of());
                for (Message message : e.getErrorMessages()) {
                    messages.add(new Message(sources, message.getMessage(), message.getCause()));
                }
            }
        }
        return messages;
    }

    Iterable<ConfigurationProvider<?>> getConfigurationProviders()
    {
        return ImmutableList.copyOf(registeredProviders);
    }

    <T> void registerConfigDefaults(ConfigDefaultsHolder<T> holder)
    {
        registeredDefaultConfigs.put(holder.getConfigKey(), holder);
    }

    private <T> ConfigDefaults<T> getConfigDefaults(Key<T> key)
    {
        ImmutableList.Builder<ConfigDefaults<T>> defaults = ImmutableList.builder();

        Key<?> globalDefaults = Key.get(key.getTypeLiteral(), GlobalDefaults.class);
        registeredDefaultConfigs.get(globalDefaults).stream()
                .map(ConfigurationFactory.<T>castHolder())
                .sorted()
                .map(ConfigDefaultsHolder::getConfigDefaults)
                .forEach(defaults::add);

        registeredDefaultConfigs.get(key).stream()
                .map(ConfigurationFactory.<T>castHolder())
                .sorted()
                .map(ConfigDefaultsHolder::getConfigDefaults)
                .forEach(defaults::add);

        return ConfigDefaults.configDefaults(defaults.build());
    }

    @SuppressWarnings("unchecked")
    private static <T> Function<ConfigDefaultsHolder<?>, ConfigDefaultsHolder<T>> castHolder()
    {
        return holder -> (ConfigDefaultsHolder<T>) holder;
    }

    <T> T getDefaultConfig(Key<T> key)
    {
        ConfigurationMetadata<T> configurationMetadata = getMetadata(key);
        configurationMetadata.getProblems().throwIfHasErrors();

        T instance = newInstance(configurationMetadata);

        ConfigDefaults<T> configDefaults = getConfigDefaults(key);
        configDefaults.setDefaults(instance);

        return instance;
    }

    public <T> T build(Class<T> configClass)
    {
        return build(configClass, null);
    }

    public <T> T build(Class<T> configClass, @Nullable String prefix)
    {
        return build(configClass, Optional.ofNullable(prefix), ConfigDefaults.noDefaults()).getInstance();
    }

    /**
     * This is used by the configuration provider
     */
    <T> T build(ConfigurationProvider<T> configurationProvider)
    {
        requireNonNull(configurationProvider, "configurationProvider");
        registerConfigurationProvider(configurationProvider, Optional.empty());

        // check for a prebuilt instance
        T instance = getCachedInstance(configurationProvider);
        if (instance != null) {
            return instance;
        }

        ConfigurationBinding<T> configurationBinding = configurationProvider.getConfigurationBinding();
        ConfigurationHolder<T> holder = build(configurationBinding.getConfigClass(), configurationBinding.getPrefix(), getConfigDefaults(configurationBinding.getKey()));
        instance = holder.getInstance();

        // inform caller about warnings
        if (warningsMonitor != null) {
            for (Message message : holder.getProblems().getWarnings()) {
                warningsMonitor.onWarning(message.toString());
            }
        }

        // add to instance cache
        T existingValue = putCachedInstance(configurationProvider, instance);
        // if key was already associated with a value, there was a
        // creation race and we lost. Just use the winners' instance;
        if (existingValue != null) {
            return existingValue;
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    private <T> T getCachedInstance(ConfigurationProvider<T> configurationProvider)
    {
        return (T) instanceCache.get(configurationProvider);
    }

    @SuppressWarnings("unchecked")
    private <T> T putCachedInstance(ConfigurationProvider<T> configurationProvider, T instance)
    {
        return (T) instanceCache.putIfAbsent(configurationProvider, instance);
    }

    private <T> ConfigurationHolder<T> build(Class<T> configClass, Optional<String> configPrefix, ConfigDefaults<T> configDefaults)
    {
        if (configClass == null) {
            throw new NullPointerException("configClass is null");
        }

        String prefix = configPrefix
                .map(value -> value + ".")
                .orElse("");
        Problems problems = new Problems();

        ConfigurationMetadata<T> configurationMetadata = getMetadata(configClass);
        problems.record(configurationMetadata.getProblems());
        problems.throwIfHasErrors();

        T instance = newInstance(configurationMetadata);

        configDefaults.setDefaults(instance);

        for (AttributeMetadata attribute : configurationMetadata.getAttributes().values()) {
            allSeenProperties.add(prefix + attribute.getInjectionPoint().getProperty());
            Problems attributeProblems = new Problems();
            try {
                setConfigProperty(instance, attribute, prefix, attributeProblems);
            }
            catch (InvalidConfigurationException e) {
                attributeProblems.addError(e.getCause(), e.getMessage());
            }
            problems.record(attributeProblems);
        }

        // Check that none of the defunct properties are still in use
        if (configClass.isAnnotationPresent(DefunctConfig.class)) {
            for (String value : configClass.getAnnotation(DefunctConfig.class).value()) {
                String name = prefix + value;
                if (!value.isEmpty() && properties.get(name) != null) {
                    problems.addError("Defunct property '%s' (class [%s]) cannot be configured.", name, configClass.toString());
                }
            }
        }

        // if there already problems, don't run the bean validation as it typically reports duplicate errors
        problems.throwIfHasErrors();

        for (ConstraintViolation<?> violation : validate(instance)) {
            String propertyFieldName = violation.getPropertyPath().toString();
            // upper case first character to match config attribute name
            String attributeName = LOWER_CAMEL.to(UPPER_CAMEL, propertyFieldName);
            AttributeMetadata attribute = configurationMetadata.getAttributes().get(attributeName);
            if (attribute != null && attribute.getInjectionPoint() != null) {
                String propertyName = attribute.getInjectionPoint().getProperty();
                if (!prefix.isEmpty()) {
                    propertyName = prefix + propertyName;
                }
                problems.addError("Invalid configuration property %s: %s (for class %s.%s)",
                        propertyName, violation.getMessage(), configClass.getName(), violation.getPropertyPath());
            }
            else {
                problems.addError("Invalid configuration property with prefix '%s': %s (for class %s.%s)",
                        prefix, violation.getMessage(), configClass.getName(), violation.getPropertyPath());
            }
        }
        problems.throwIfHasErrors();

        return new ConfigurationHolder<>(instance, problems);
    }

    private static <T> Set<ConstraintViolation<T>> validate(T instance)
    {
        synchronized (VALIDATOR) {
            return VALIDATOR.validate(instance);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ConfigurationMetadata<T> getMetadata(Key<T> key)
    {
        return getMetadata((Class<T>) key.getTypeLiteral().getRawType());
    }

    @SuppressWarnings("unchecked")
    private <T> ConfigurationMetadata<T> getMetadata(Class<T> configClass)
    {
        return (ConfigurationMetadata<T>) METADATA_CACHE.getUnchecked(configClass);
    }

    private static <T> T newInstance(ConfigurationMetadata<T> configurationMetadata)
    {
        try {
            return configurationMetadata.getConstructor().newInstance();
        }
        catch (Throwable e) {
            if (e instanceof InvocationTargetException && e.getCause() != null) {
                e = e.getCause();
            }
            throw exceptionFor(e, "Error creating instance of configuration class [%s]", configurationMetadata.getConfigClass().getName());
        }
    }

    private <T> void setConfigProperty(T instance, AttributeMetadata attribute, String prefix, Problems problems)
            throws InvalidConfigurationException
    {
        // Get property value
        ConfigurationMetadata.InjectionPointMetaData injectionPoint = findOperativeInjectionPoint(attribute, prefix, problems);

        // If we did not get an injection point, do not call the setter
        if (injectionPoint == null) {
            return;
        }

        if (injectionPoint.getSetter().isAnnotationPresent(Deprecated.class)) {
            problems.addWarning(describeDeprecation(prefix, injectionPoint));
        }

        Object value = getInjectedValue(attribute, injectionPoint, prefix);

        try {
            injectionPoint.getSetter().invoke(instance, value);
        }
        catch (Throwable e) {
            if (e instanceof InvocationTargetException && e.getCause() != null) {
                e = e.getCause();
            }
            throw new InvalidConfigurationException(e, format("Error invoking configuration method [%s]", injectionPoint.getSetter().toGenericString()));
        }
    }

    private static String describeDeprecation(String prefix, ConfigurationMetadata.InjectionPointMetaData injectionPoint)
    {
        Deprecated deprecated = injectionPoint.getSetter().getAnnotation(Deprecated.class);
        String deprecationNotice = "Configuration property '%s' is deprecated".formatted(prefix + injectionPoint.getProperty());
        if (!deprecated.since().isBlank()) {
            deprecationNotice = deprecationNotice + " since " + deprecated.since();
        }
        return deprecationNotice + (deprecated.forRemoval() ? " and will be removed in the future" : " and should not be used");
    }

    private ConfigurationMetadata.InjectionPointMetaData findOperativeInjectionPoint(AttributeMetadata attribute, String prefix, Problems problems)
            throws ConfigurationException
    {
        ConfigurationMetadata.InjectionPointMetaData operativeInjectionPoint = attribute.getInjectionPoint();
        String operativeName = null;
        String operativeValue = null;
        if (operativeInjectionPoint != null) {
            operativeName = prefix + operativeInjectionPoint.getProperty();
            operativeValue = properties.get(operativeName);
        }
        String printableOperativeValue = operativeValue;
        if (attribute.isSecuritySensitive()) {
            printableOperativeValue = "[REDACTED]";
        }

        for (ConfigurationMetadata.InjectionPointMetaData injectionPoint : attribute.getLegacyInjectionPoints()) {
            String fullName = prefix + injectionPoint.getProperty();
            String value = properties.get(fullName);
            String printableValue = value;
            if (attribute.isSecuritySensitive()) {
                printableValue = "[REDACTED]";
            }
            if (value != null) {
                String replacement = "deprecated.";
                if (attribute.getInjectionPoint() != null) {
                    replacement = format("replaced. Use '%s' instead.", prefix + attribute.getInjectionPoint().getProperty());
                }
                problems.addWarning("Configuration property '%s' has been " + replacement, fullName);

                if (operativeValue == null) {
                    operativeInjectionPoint = injectionPoint;
                    operativeValue = value;
                    printableOperativeValue = printableValue;
                    operativeName = fullName;
                }
                else {
                    problems.addError("Configuration property '%s' (=%s) conflicts with property '%s' (=%s)", fullName, printableValue, operativeName, printableOperativeValue);
                }
            }
        }

        problems.throwIfHasErrors();
        if (operativeValue == null) {
            // No injection from configuration
            return null;
        }

        return operativeInjectionPoint;
    }

    private Object getInjectedValue(AttributeMetadata attribute, ConfigurationMetadata.InjectionPointMetaData injectionPoint, String prefix)
            throws InvalidConfigurationException
    {
        String name = prefix + injectionPoint.getProperty();
        usedProperties.add(new ConfigPropertyMetadata(name, attribute.isSecuritySensitive()));

        // Get the property value
        String value = properties.get(name);
        String printableValue = value;
        if (attribute.isSecuritySensitive()) {
            printableValue = "[REDACTED]";
        }

        if (value == null) {
            return null;
        }

        // coerce the property value to the final type
        TypeToken<?> propertyType = TypeToken.of(injectionPoint.getSetter().getGenericParameterTypes()[0]);
        Object finalValue = coerce(propertyType, value);
        if (finalValue == null) {
            throw new InvalidConfigurationException(format("Invalid value '%s' for type %s (property '%s') in order to call [%s]",
                    printableValue,
                    propertyType.getType().getTypeName(),
                    name,
                    injectionPoint.getSetter().toGenericString()));
        }
        return finalValue;
    }

    private static Object coerce(TypeToken<?> type, String value)
    {
        if (type.isPrimitive() && value == null) {
            return null;
        }

        try {
            if (String.class == type.getRawType()) {
                return value;
            }
            if (Boolean.class == type.getRawType() || boolean.class == type.getRawType()) {
                // Boolean.valueOf returns `false` when called with `"true "` argument
                if ("true".equalsIgnoreCase(value)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(value)) {
                    return Boolean.FALSE;
                }
                return null;
            }
            if (Byte.class == type.getRawType() || byte.class == type.getRawType()) {
                return Byte.valueOf(value);
            }
            if (Short.class == type.getRawType() || short.class == type.getRawType()) {
                return Short.valueOf(value);
            }
            if (Integer.class == type.getRawType() || int.class == type.getRawType()) {
                return Integer.valueOf(value);
            }
            if (Long.class == type.getRawType() || long.class == type.getRawType()) {
                return Long.valueOf(value);
            }
            if (Float.class == type.getRawType() || float.class == type.getRawType()) {
                return Float.valueOf(value);
            }
            if (Double.class == type.getRawType() || double.class == type.getRawType()) {
                return Double.valueOf(value);
            }
            if (URI.class == type.getRawType()) {
                return URI.create(value);
            }
        }
        catch (Exception ignored) {
            // ignore the random exceptions from the built in types
            return null;
        }

        Map<String, Method> stringAcceptingMethods = stream(type.getRawType().getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(ConfigurationFactory::acceptsSingleStringParameter)
                .collect(toImmutableMap(Method::getName, identity()));

        // Look for a static fromString(String) methods. This is used in preference
        // to the built-in valueOf() method for enums.
        if (stringAcceptingMethods.get("fromString") instanceof Method fromString) {
            if (type.isSubtypeOf(fromString.getGenericReturnType())) {
                try {
                    return invokeFactory(fromString, value);
                }
                catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }

        if (type.isSubtypeOf(TypeToken.of(Enum.class))) {
            try {
                return Enum.valueOf(type.getRawType().asSubclass(Enum.class), value.toUpperCase(ENGLISH));
            }
            catch (IllegalArgumentException ignored) {
            }

            Object match = null;
            for (Enum<?> option : type.getRawType().asSubclass(Enum.class).getEnumConstants()) {
                String enumValue = value.replace("-", "_");
                if (option.name().equalsIgnoreCase(enumValue)) {
                    if (match != null) {
                        // Ambiguity
                        return null;
                    }
                    match = option;
                }
            }
            return match;
        }

        if (type.isSubtypeOf(TypeToken.of(Set.class))) {
            TypeToken<?> argumentToken = getActualTypeArgument(type);

            return VALUE_SPLITTER.splitToStream(value)
                    .map(item -> coerce(argumentToken, item))
                    .collect(toImmutableSet());
        }

        if (type.isSubtypeOf(TypeToken.of(List.class))) {
            TypeToken<?> argumentToken = getActualTypeArgument(type);

            return VALUE_SPLITTER.splitToStream(value)
                    .map(item -> coerce(argumentToken, item))
                    .collect(toImmutableList());
        }

        if (type.isSubtypeOf(TypeToken.of(Optional.class))) {
            TypeToken<?> argumentToken = getActualTypeArgument(type);
            return Optional.ofNullable(coerce(argumentToken, value));
        }

        // Look for a static valueOf(String) method
        if (stringAcceptingMethods.get("valueOf") instanceof Method valueOf) {
            if (type.isSubtypeOf(valueOf.getGenericReturnType())) {
                try {
                    return invokeFactory(valueOf, value);
                }
                catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }

        // Look for a static of(String) method
        if (stringAcceptingMethods.get("of") instanceof Method of) {
            if (type.isSubtypeOf(of.getGenericReturnType())) {
                try {
                    return invokeFactory(of, value);
                }
                catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }

        // Look for a constructor taking a string
        for (Constructor<?> constructor : type.getRawType().getConstructors()) {
            if (acceptsSingleStringParameter(constructor)) {
                try {
                    if (constructor.isVarArgs()) {
                        return constructor.newInstance(value, new String[0]);
                    }
                    return constructor.newInstance(value);
                }
                catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }

        return null;
    }

    private static boolean acceptsSingleStringParameter(Executable executable)
    {
        if (executable.getParameterCount() == 1) {
            return executable.getParameters()[0].getType() == String.class;
        }

        if (executable.isVarArgs() && executable.getParameterCount() == 2) {
            return executable.getParameters()[0].getType() == String.class
                    && executable.getParameters()[1].getType() == String[].class;
        }

        return false;
    }

    private static TypeToken<?> getActualTypeArgument(TypeToken<?> type)
    {
        ParameterizedType argumentType = (ParameterizedType) type.getType();
        verify(argumentType.getActualTypeArguments().length == 1, "Expected type %s to be parametrized", type);
        return TypeToken.of(argumentType.getActualTypeArguments()[0]);
    }

    private static Object invokeFactory(Method factory, String value)
            throws ReflectiveOperationException
    {
        if (factory.isVarArgs()) {
            return factory.invoke(null, value, new String[0]);
        }
        return factory.invoke(null, value);
    }

    private static class ConfigurationHolder<T>
    {
        private final T instance;
        private final Problems problems;

        private ConfigurationHolder(T instance, Problems problems)
        {
            this.instance = instance;
            this.problems = problems;
        }

        public T getInstance()
        {
            return instance;
        }

        public Problems getProblems()
        {
            return problems;
        }
    }

    private class ConfigurationProviderConsumer
            implements Consumer<ConfigurationProvider<?>>
    {
        private final ConfigurationBindingListener listener;
        private final ConfigBinder configBinder;

        public ConfigurationProviderConsumer(ConfigurationBindingListener listener)
        {
            this.listener = listener;
            this.configBinder = ConfigBinder.configBinder(ConfigurationFactory.this, Optional.of(listener));
        }

        @Override
        public void accept(ConfigurationProvider<?> configurationProvider)
        {
            listener.configurationBound(configurationProvider.getConfigurationBinding(), configBinder);
        }
    }
}
