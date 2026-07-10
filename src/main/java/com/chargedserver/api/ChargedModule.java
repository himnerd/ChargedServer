package com.chargedserver.api;

public interface ChargedModule {

    String name();

    void onLoad(ChargedAPI api);

    default void onUnload() {
    }
}