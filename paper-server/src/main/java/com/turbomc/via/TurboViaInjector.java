package com.turbomc.via;

import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.gson.JsonObject;

public class TurboViaInjector implements ViaInjector {

    @Override
    public void inject() throws Exception {
        // Handled manually in ServerConnectionListener
    }

    @Override
    public void uninject() throws Exception {
    }

    @Override
    public int getServerProtocolVersion() throws Exception {
        return 761; // 1.19.4 protocol version
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
    public com.viaversion.viaversion.libs.gson.JsonObject getDump() {
        return new com.viaversion.viaversion.libs.gson.JsonObject();
    }
}
