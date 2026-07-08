package com.vaultsurvival.plugin.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages module lifecycle: loading, enabling, and disabling modules.
 * Ensures modules are loaded in dependency order.
 */
public class ModuleManager {

    private final Logger logger;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final List<String> loadOrder = new ArrayList<>();

    public ModuleManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Register a module with the manager.
     * Modules are enabled in registration order (after dependency resolution).
     */
    public void registerModule(Module module) {
        String name = module.getName();
        if (modules.containsKey(name)) {
            logger.warning("Module already registered: " + name);
            return;
        }
        modules.put(name, module);
        logger.info("Registered module: " + name);
    }

    /**
     * Load all registered modules (call onLoad for each).
     */
    public void loadAll() {
        // Resolve dependencies and determine load order
        resolveOrder();

        for (String name : loadOrder) {
            Module module = modules.get(name);
            try {
                logger.info("Loading module: " + name);
                module.onLoad();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to load module: " + name, e);
            }
        }
    }

    /**
     * Enable all registered modules (call onEnable for each).
     */
    public void enableAll() {
        for (String name : loadOrder) {
            Module module = modules.get(name);
            if (module == null || module.isEnabled()) continue;
            try {
                logger.info("Enabling module: " + name);
                module.onEnable();
                module.setEnabled(true);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to enable module: " + name, e);
            }
        }
    }

    /**
     * Disable all modules in reverse load order.
     */
    public void disableAll() {
        for (int i = loadOrder.size() - 1; i >= 0; i--) {
            String name = loadOrder.get(i);
            Module module = modules.get(name);
            if (module == null || !module.isEnabled()) continue;
            try {
                logger.info("Disabling module: " + name);
                module.onDisable();
                module.setEnabled(false);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to disable module: " + name, e);
            }
        }
    }

    /**
     * Get a module by name.
     */
    public Module getModule(String name) {
        return modules.get(name);
    }

    /**
     * Check if a module is registered and enabled.
     */
    public boolean isEnabled(String name) {
        Module module = modules.get(name);
        return module != null && module.isEnabled();
    }

    /**
     * @return list of all registered module names.
     */
    public List<String> getModuleNames() {
        return new ArrayList<>(modules.keySet());
    }

    /**
     * Resolve load order using simple dependency resolution.
     * Ensures a module's dependencies are loaded before it.
     */
    private void resolveOrder() {
        loadOrder.clear();
        Map<String, Boolean> visited = new LinkedHashMap<>();

        for (String name : modules.keySet()) {
            if (!visited.containsKey(name)) {
                resolveDfs(name, visited, new ArrayList<>());
            }
        }
    }

    private void resolveDfs(String name, Map<String, Boolean> visited, List<String> path) {
        if (path.contains(name)) {
            logger.warning("Circular dependency detected involving: " + name);
            return;
        }
        if (Boolean.TRUE.equals(visited.get(name))) return;

        path.add(name);
        Module module = modules.get(name);
        if (module != null) {
            for (String dep : module.getDependencies()) {
                if (!modules.containsKey(dep)) {
                    logger.warning("Module '" + name + "' depends on '" + dep +
                                   "' but it is not registered! Loading may fail.");
                }
                resolveDfs(dep, visited, path);
            }
        } else {
            logger.warning("Module '" + name + "' is referenced as a dependency but is not registered.");
        }
        path.remove(path.size() - 1);

        visited.put(name, true);
        loadOrder.add(name);
    }
}
