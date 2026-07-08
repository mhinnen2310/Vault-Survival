package com.vaultsurvival.plugin.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Lightweight service locator / registry.
 * Modules register their service implementations here, and other modules
 * retrieve them by interface. This keeps modules decoupled.
 */
public class ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    /**
     * Register a service implementation for an interface type.
     */
    public <T> void register(Class<T> serviceType, T implementation) {
        services.put(serviceType, implementation);
    }

    /**
     * Retrieve a registered service by its interface type.
     *
     * @throws IllegalStateException if the service is not registered.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> serviceType) {
        T service = (T) services.get(serviceType);
        if (service == null) {
            throw new IllegalStateException(
                "Service not registered: " + serviceType.getSimpleName()
            );
        }
        return service;
    }

    /**
     * Check if a service is registered.
     */
    public <T> boolean has(Class<T> serviceType) {
        return services.containsKey(serviceType);
    }

    /**
     * Remove a service from the registry (useful for hot-reloading modules).
     */
    public <T> void unregister(Class<T> serviceType) {
        services.remove(serviceType);
    }

    /**
     * Clear all registered services. Called on plugin shutdown.
     */
    public void clear() {
        services.clear();
    }

    /**
     * @return number of registered services.
     */
    public int size() {
        return services.size();
    }
}
