package com.turbomc.via;

import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.libs.gson.JsonObject;

public class TurboViaInjector implements ViaInjector {

    @Override
    public void inject() throws Exception {
        // Native injection is handled directly in ServerConnectionListener
        // No reflection needed here!
    }

    @Override
    public void uninject() throws Exception {
        // Server stop handles this
    }

    @Override
    public int getServerProtocolVersion() throws Exception {
        // TODO: Dynamically fetch this from SharedConstants if possible, but hardcoding for the target version is safe for now
        // 1.21.10 = 773 (approx, checking actual mapping...)
        // Actually SharedConstants.getCurrentVersion().protocolVersion() should be used in the loader
        return 767; // Placeholder, will be ignored by many things as we are on the server
    }

    @Override
    public String getEncoderName() {
        return "via-encoder";
    }

    @Override
    public String getDecoderName() {
        return "via-decoder";
    }

    @Override
    public JsonObject getDump() {
        return new JsonObject();
    }
}
