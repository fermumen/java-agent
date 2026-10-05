package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class WindowsNetworkDefaultsTest {
    @Test
    void windowsUsesSystemProxyAndWindowsTrustStore() {
        Properties properties = os("Windows 11");
        WindowsNetworkDefaults.apply(Map.of(), properties, type -> true);

        assertEquals("true", properties.getProperty("java.net.useSystemProxies"));
        assertEquals("Windows-ROOT", properties.getProperty("javax.net.ssl.trustStoreType"));
    }

    @Test
    void explicitSettingsWin() {
        Properties properties = os("Windows 11");
        properties.setProperty("https.proxyHost", "proxy.example");
        properties.setProperty("javax.net.ssl.trustStore", "C:\\certs\\corp.jks");
        WindowsNetworkDefaults.apply(Map.of(), properties, type -> true);

        assertFalse(properties.containsKey("java.net.useSystemProxies"));
        assertFalse(properties.containsKey("javax.net.ssl.trustStoreType"));

        Properties disabled = os("Windows 11");
        disabled.setProperty("java.net.useSystemProxies", "false");
        disabled.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        WindowsNetworkDefaults.apply(Map.of(), disabled, type -> true);
        assertEquals("false", disabled.getProperty("java.net.useSystemProxies"));
        assertEquals("PKCS12", disabled.getProperty("javax.net.ssl.trustStoreType"));
    }

    @Test
    void optOutUnavailableStoreAndOtherPlatformsLeaveJdkDefaults() {
        Properties optedOut = os("Windows 10");
        WindowsNetworkDefaults.apply(Map.of(WindowsNetworkDefaults.OPT_OUT, "0"), optedOut, type -> true);
        assertNull(optedOut.getProperty("java.net.useSystemProxies"));
        assertNull(optedOut.getProperty("javax.net.ssl.trustStoreType"));

        Properties noStore = os("Windows 10");
        WindowsNetworkDefaults.apply(Map.of(), noStore, type -> false);
        assertEquals("true", noStore.getProperty("java.net.useSystemProxies"));
        assertNull(noStore.getProperty("javax.net.ssl.trustStoreType"));

        Properties linux = os("Linux");
        WindowsNetworkDefaults.apply(Map.of(), linux, type -> true);
        assertNull(linux.getProperty("java.net.useSystemProxies"));
        assertNull(linux.getProperty("javax.net.ssl.trustStoreType"));
    }

    private static Properties os(String name) {
        Properties properties = new Properties();
        properties.setProperty("os.name", name);
        return properties;
    }
}
