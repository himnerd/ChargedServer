package com.chargedserver.pluginmanager;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Merges default configuration files bundled inside an updated plugin jar
 * into the plugin's existing data folder. Existing values are never touched
 * or cleared — only keys missing from the current config are added. Config
 * files that don't exist yet are copied verbatim (preserving comments).
 */
public final class ConfigMerger {

    private ConfigMerger() {
    }

    /**
     * Scans the jar for bundled .yml/.yaml defaults and merges them into
     * dataFolder. Returns the total number of new keys added across all
     * files (copied files count their leaf keys).
     */
    public static int mergeFromJar(File jar, File dataFolder, Logger logger) {
        int added = 0;
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !isConfigResource(name)) {
                    continue;
                }
                try {
                    added += mergeEntry(jarFile, entry, dataFolder);
                } catch (Exception e) {
                    logger.warning("Config merge failed for " + jar.getName()
                            + " -> " + name + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("Could not scan " + jar.getName() + " for config changes: " + e.getMessage());
        }
        return added;
    }

    private static boolean isConfigResource(String name) {
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            return false;
        }
        // Plugin descriptors are not user configs.
        if (name.equals("plugin.yml") || name.equals("paper-plugin.yml") || name.equals("bungee.yml")) {
            return false;
        }
        // Only root-level files and common config directories — deeper paths
        // are usually internal resources (lang templates, schematics, etc.).
        int depth = (int) name.chars().filter(c -> c == '/').count();
        return depth == 0 || (depth == 1
                && (name.startsWith("config/") || name.startsWith("configs/")
                || name.startsWith("lang/") || name.startsWith("language/")
                || name.startsWith("languages/") || name.startsWith("messages/")
                || name.startsWith("locale/") || name.startsWith("locales/")));
    }

    private static int mergeEntry(JarFile jarFile, JarEntry entry, File dataFolder) throws Exception {
        File target = new File(dataFolder, entry.getName());
        if (!target.toPath().normalize().startsWith(dataFolder.toPath().normalize())) {
            return 0;
        }

        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(
                jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        if (defaults.getKeys(true).isEmpty()) {
            return 0;
        }

        if (!target.exists()) {
            // Brand-new config file — copy raw bytes so comments survive.
            target.getParentFile().mkdirs();
            try (InputStream in = jarFile.getInputStream(entry)) {
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return (int) defaults.getKeys(true).stream()
                    .filter(key -> !defaults.isConfigurationSection(key))
                    .count();
        }

        YamlConfiguration existing = YamlConfiguration.loadConfiguration(target);
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!existing.contains(key)) {
                existing.set(key, defaults.get(key));
                added++;
            }
        }
        if (added > 0) {
            existing.save(target);
        }
        return added;
    }
}