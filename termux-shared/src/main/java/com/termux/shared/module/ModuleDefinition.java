package com.termux.shared.module;

public class ModuleDefinition {

    public final String id;
    public final String name;
    public final String description;
    public final String repo;
    public final String script;
    public final String icon;

    private String mStatus;
    private String mVersion;
    private String mError;

    public ModuleDefinition(String id, String name, String description,
                            String repo, String script, String icon) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.repo = repo;
        this.script = script;
        this.icon = icon;
        this.mStatus = "not_installed";
        this.mVersion = "";
        this.mError = null;
    }

    public String getStatus() { return mStatus; }
    public void setStatus(String status) { this.mStatus = status; }

    public String getVersion() { return mVersion; }
    public void setVersion(String version) { this.mVersion = version; }

    public String getError() { return mError; }
    public void setError(String error) { this.mError = error; }

    public boolean isInstalled() {
        return "installed".equals(mStatus) || "running".equals(mStatus);
    }

    public boolean isRunning() {
        return "running".equals(mStatus);
    }
}
