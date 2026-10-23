package pojlib.install;

import com.google.gson.annotations.SerializedName;
import pojlib.APIHandler;
import pojlib.util.Constants;
import pojlib.util.Version;

public class FabricMeta {

    private static final APIHandler handler = new APIHandler(Constants.FABRIC_META_URL);

    public static class FabricVersion {
        @SerializedName("version")
        public String version;
        @SerializedName("stable")
        public boolean stable;
        @SerializedName("separator")
        public String separator;
    }

    public static FabricVersion[] getVersions() {
        return handler.get("versions/loader", FabricVersion[].class);
    }

    private static Version getVersionFromFabric(FabricVersion fabric) {
        if(fabric.separator.contains("+")) {
            // Only used pre-0.11, no use for us
            return null;
        }

        String[] verName = fabric.version.split("\\.");
        if(verName.length < 3) {
            return null;
        }
        int major, minor, patch;
        try {
            major = Integer.parseInt(verName[0]);
            minor = Integer.parseInt(verName[1]);
            patch = Integer.parseInt(verName[2]);
        } catch (NumberFormatException e) {
            // pre-release tags like 0.17.0-beta.1
            return null;
        }

        return new Version(major, minor, patch);
    }

    static int compare(Version a, Version b) {
        if(a.major != b.major) return Integer.compare(a.major, b.major);
        if(a.minor != b.minor) return Integer.compare(a.minor, b.minor);
        return Integer.compare(a.patch, b.patch);
    }

    public static FabricVersion getLatestVersion() {
        FabricVersion latest = null;
        Version latestVer = null;
        for (FabricVersion version : getVersions()) {
            if(!version.stable)
                continue;

            Version newVer = getVersionFromFabric(version);
            if(newVer == null)
                continue;

            if(latestVer == null || compare(newVer, latestVer) > 0) {
                latest = version;
                latestVer = newVer;
            }
        }
        return latest;
    }

    public static VersionInfo getVersionInfo(FabricVersion fabricVersion, String minecraftVersion) {
        return handler.get(String.format("versions/loader/%s/%s/profile/json", minecraftVersion, fabricVersion.version), VersionInfo.class);
    }
}