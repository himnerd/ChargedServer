package com.chargedserver.pluginmanager;

import lombok.Data;

@Data
public class PluginInfo {

    private String name;
    private String version;
    private String description;
    private String fileName;
    private String website;
    private String modrinthId;
    private String hangarSlug;
    private Integer spigotId;
    private String githubRepo;
    private String latestVersion;
    private String downloadUrl;
    private String source;
    private boolean updateAvailable;
    private boolean updateQueued;
}