package org.bukkit.craftbukkit.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

public final class Versioning {
    public static String getBukkitVersion() {
        // TurboMC: Use valid version format for plugin compatibility
        String result = "1.21.10-R0.1";

        InputStream stream = Bukkit.class.getClassLoader().getResourceAsStream("META-INF/maven/io.papermc.paper/paper-api/pom.properties");
        Properties properties = new Properties();

        if (stream != null) {
            try {
                properties.load(stream);

                result = properties.getProperty("version");
            } catch (IOException ex) {
                Logger.getLogger(Versioning.class.getName()).log(Level.SEVERE, "Could not get Bukkit version!", ex);
            }
        }

        return result;
    }
}
