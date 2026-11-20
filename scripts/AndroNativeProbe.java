import libv2ray.Libv2ray;

/** Device-side smoke check: load JNI, initialize the core, open and close a local exit. */
public class AndroNativeProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("CORE_LOADED " + Libv2ray.checkVersionX());
        Libv2ray.initCoreEnv("/data/local/tmp/androng-native-probe", "");
        long port = Libv2ray.openExit("{\"outbounds\":[{\"tag\":\"exit-node\",\"protocol\":\"freedom\"}]}");
        if (port <= 0) throw new IllegalStateException("Exit failed to open");
        Libv2ray.closeExit();
        System.out.println("EXIT_OPEN_CLOSE_OK");
    }
}
