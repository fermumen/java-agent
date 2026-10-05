package dev.fxjava;

import java.security.KeyStore;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * Corporate Windows hosts usually route HTTPS through the system proxy and a
 * TLS-inspecting gateway whose root certificate lives only in the Windows
 * certificate store. The JDK ignores both by default, so on Windows this
 * opts into the system proxy and the Windows-ROOT trust store unless the user
 * configured either explicitly. Set {@code JAVA_AGENT_SYSTEM_NETWORK=0} to opt out.
 */
final class WindowsNetworkDefaults {
    static final String OPT_OUT = "JAVA_AGENT_SYSTEM_NETWORK";

    private WindowsNetworkDefaults() { }

    /** Must run before the first proxy selector or SSL context is created. */
    static void apply(Map<String, String> environment, Properties properties) {
        apply(environment, properties, WindowsNetworkDefaults::keyStoreAvailable);
    }

    static void apply(Map<String, String> environment, Properties properties, Predicate<String> keyStoreAvailable) {
        String os = properties.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("windows")) return;
        String optOut = environment.getOrDefault(OPT_OUT, "").trim().toLowerCase(Locale.ROOT);
        if (optOut.equals("0") || optOut.equals("false") || optOut.equals("off")) return;

        if (!properties.containsKey("java.net.useSystemProxies")
                && !properties.containsKey("https.proxyHost") && !properties.containsKey("http.proxyHost")) {
            properties.setProperty("java.net.useSystemProxies", "true");
        }
        if (!properties.containsKey("javax.net.ssl.trustStore")
                && !properties.containsKey("javax.net.ssl.trustStoreType")
                && keyStoreAvailable.test("Windows-ROOT")) {
            properties.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
        }
    }

    private static boolean keyStoreAvailable(String type) {
        try {
            KeyStore.getInstance(type);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
