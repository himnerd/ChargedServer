package com.chargedserver.pluginmanager;

import lombok.Data;

@Data
public class PluginInfo {

    private String name;
    private String version;
    private String description;
    private String fileName;
    private String modrinthId;
    private String latestVersion;
    private String downloadUrl;
    private boolean updateAvailable;
    private boolean updateQueued;
}