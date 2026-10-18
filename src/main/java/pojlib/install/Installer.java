package pojlib.install;

import android.app.Activity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.commons.io.FileUtils;

import pojlib.APIHandler;
import pojlib.util.download.DownloadManager;
import pojlib.util.download.DownloadUtils;
import pojlib.util.json.MinecraftInstances;
import pojlib.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import pojlib.util.json.ProjectInfo;

//This class reads data from a game version json and downloads its contents.
//This works for the base game as well as mod loaders
public class Installer {

    private static final String IMMEDIATELY_FAST_URL = "https://cdn.modrinth.com/data/5ZwdcRci/versions/iwYUrQJO/ImmediatelyFast-Fabric-1.5.5%2B1.20.4.jar";
    private static final String IMMEDIATELY_FAST_SHA1 = "9f0110b60dc752ca093f2fc10a72d221b6dc8ac3";

    private static final String[] REQUIRED_JRE_FILES = {
            "bin/java",
            "release",
            "lib/libjli.so",
            "lib/libjava.so",
            "lib/libverify.so",
            "lib/libnet.so",
            "lib/libnio.so",
            "lib/libawt.so",
            "lib/libawt_headless.so",
            "lib/libfreetype.so",
            "lib/libfontmanager.so",
            "lib/server/libjvm.so"
    };

    public static synchronized void installJVM(Activity activity) throws IOException {
        Logger.getInstance().appendToLog("Checking JRE");
        File runtimes = new File(activity.getFilesDir(), "runtimes");
        File jre = new File(runtimes, "JRE");
        File installing = new File(runtimes, "JRE.installing");
        File jreZip = new File(runtimes, "JRE.zip.download");
        String jreURL = "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/latest/download/JRE.zip";

        if (isJreValid(jre)) {
            Logger.getInstance().appendToLog("JRE installed");
            return;
        }

        Logger.getInstance().appendToLog(jre.exists() ? "JRE incomplete, reinstalling" : "Installing JRE");
        FileUtils.forceMkdir(runtimes);
        FileUtils.deleteQuietly(installing);
        FileUtils.deleteQuietly(jreZip);

        try {
            DownloadUtils.downloadFile(jreURL, jreZip);
            DownloadManager.reset();
            FileUtil.unzipArchiveChecked(jreZip.getPath(), installing.getPath());
            Files.copy(
                    Paths.get(activity.getApplicationInfo().nativeLibraryDir, "libawt_xawt.so"),
                    Paths.get(installing.getPath(), "lib", "libawt_xawt.so"),
                    StandardCopyOption.REPLACE_EXISTING
            );

            if (!isJreValid(installing)) {
                throw new IOException("Downloaded JRE is incomplete");
            }

            FileUtils.deleteQuietly(jre);
            Files.move(installing.toPath(), jre.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Logger.getInstance().appendToLog("JRE installed");
        } catch (IOException e) {
            Logger.getInstance().appendToLog("Failed to install JRE: " + e.getMessage());
            throw e;
        } finally {
            FileUtils.deleteQuietly(installing);
            FileUtils.deleteQuietly(jreZip);
            DownloadManager.reset();
        }
    }

    private static boolean isJreValid(File jre) {
        if (!jre.isDirectory()) {
            return false;
        }
        for (String path : REQUIRED_JRE_FILES) {
            File file = new File(jre, path);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }

    // Will only download client if it is missing, however it will overwrite if sha1 does not match the downloaded client
    // Returns client classpath
    public static CompletableFuture<String> installClient(VersionInfo minecraftVersionInfo, String gameDir) throws IOException {
        return CompletableFuture.supplyAsync(() -> {
            Logger.getInstance().appendToLog("Checking Client");

            File clientFile = new File(gameDir + "/versions/" + minecraftVersionInfo.id + "/client.jar");

            try {
                for (int i = 0; i < 5; i++) {
                    if (i == 4)
                        throw new RuntimeException("Client download failed after 5 retries");

                    if (!clientFile.exists()) {
                        DownloadUtils.downloadFile(minecraftVersionInfo.downloads.client.url, clientFile);
                    } else if (DownloadUtils.compareSHA1(clientFile, minecraftVersionInfo.downloads.client.sha1)) {
                        clientFile.delete();
                        DownloadUtils.downloadFile(minecraftVersionInfo.downloads.client.url, clientFile);
                    }

                    // Check if the downloaded client matches the expected SHA1 hash
                    if (DownloadUtils.compareSHA1(clientFile, minecraftVersionInfo.downloads.client.sha1)) {
                        Logger.getInstance().appendToLog("Client downloaded");
                        return clientFile.getAbsolutePath();
                    }
                }
            } catch (IOException e) {
                Logger.getInstance().appendToLog("Failed to download client: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        });
    }

    // Will only download library if it is missing, however it will overwrite if sha1 does not match the downloaded library
    // Returns the classpath of the downloaded libraries
    public static CompletableFuture<String> installLibraries(VersionInfo versionInfo, String gameDir) throws IOException {
        return CompletableFuture.supplyAsync(() -> {
            Logger.getInstance().appendToLog("Checking Libraries for: " + versionInfo.id);
            StringJoiner classpath = new StringJoiner(File.pathSeparator);

            for (VersionInfo.Library library : versionInfo.libraries) {
                if (library.name.contains("lwjgl") || (library.name.contains("org.ow2.asm")) & !versionInfo.id.contains("fabric")) {
                    continue;
                }
                for (int i = 0; i < 5; i++) {
                    if (i == 4)
                        throw new RuntimeException(String.format("Library download of %s failed after 5 retries", library.name));

                    File libraryFile;
                    String sha1;

                    //Null means mod lib, otherwise vanilla lib
                    try {
                        if (library.downloads == null) {
                            String path = parseLibraryNameToPath(library.name);
                            libraryFile = new File(gameDir + "/libraries/", path);
                            sha1 = APIHandler.getRaw(library.url + path + ".sha1");
                            if (!libraryFile.exists()) {
                                Logger.getInstance().appendToLog("Downloading: " + library.name);
                                DownloadUtils.downloadFile(library.url + path, libraryFile);
                            }
                        } else {
                            VersionInfo.Library.Artifact artifact = library.downloads.artifact;
                            libraryFile = new File(gameDir + "/libraries/", artifact.path);
                            sha1 = artifact.sha1;
                            if (!libraryFile.exists()) {
                                Logger.getInstance().appendToLog("Downloading: " + library.name);
                                DownloadUtils.downloadFile(artifact.url, libraryFile, artifact.size);
                            }
                        }
                        if (DownloadUtils.compareSHA1(libraryFile, sha1)) {
                            classpath.add(libraryFile.getAbsolutePath());
                            break;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Add our GLFW
            classpath.add(Constants.USER_HOME + "/lwjgl3/lwjgl-glfw-classes.jar");
            // DNS SRV Resolver fix
            classpath.add(Constants.USER_HOME + "/hacks/ResConfHack.jar");

            Logger.getInstance().appendToLog("Libraries installed");
            return classpath.toString();
        });
    }

    //Only works on minecraft, not fabric, quilt, etc...
    //Will only download asset if it is missing
    public static CompletableFuture<String> installAssets(VersionInfo minecraftVersionInfo, String gameDir) throws IOException {
        return CompletableFuture.supplyAsync(() -> {
            Logger.getInstance().appendToLog("Checking assets");
            JsonObject assets = APIHandler.getFullUrl(minecraftVersionInfo.assetIndex.url, JsonObject.class);

            for (Map.Entry<String, JsonElement> entry : assets.getAsJsonObject("objects").entrySet()) {
                VersionInfo.Asset asset = new Gson().fromJson(entry.getValue(), VersionInfo.Asset.class);
                DownloadManager.addTotalBytes(asset.size);
            }

            ThreadPoolExecutor tp = new ThreadPoolExecutor(8, 8, 100, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

            for (Map.Entry<String, JsonElement> entry : assets.getAsJsonObject("objects").entrySet()) {
                AsyncDownload thread = new AsyncDownload(entry, gameDir);
                tp.execute(thread);
            }

            tp.shutdown();
            try {
                while (!tp.awaitTermination(100, TimeUnit.MILLISECONDS)) ;
            } catch (InterruptedException e) {
                Logger.getInstance().appendToLog("Download thread interrupted" + e.getMessage());
            }

            File indexJson = new File(gameDir + "/assets/indexes/" + minecraftVersionInfo.assets + ".json");
            if (!indexJson.exists()) {
                try {
                    DownloadUtils.downloadFile(minecraftVersionInfo.assetIndex.url, indexJson);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return new File(gameDir + "/assets").getAbsolutePath();
        });
    }

    public static void moveLocalAssets(Activity activity, MinecraftInstances.Instance instance) throws IOException {
        File config = new File(instance.gameDir, "config");
        FileUtil.ensureDirectory(config);
        FileUtils.writeByteArrayToFile(new File(config, "sodium-options.json"), FileUtil.loadFromAssetToByte(activity, "sodium-options.json"));
        FileUtils.writeByteArrayToFile(new File(config, "smoothboot.json"), FileUtil.loadFromAssetToByte(activity, "smoothboot.json"));
        FileUtils.writeByteArrayToFile(new File(config, "immediatelyfast.json"), FileUtil.loadFromAssetToByte(activity, "immediatelyfast.json"));
        FileUtils.writeByteArrayToFile(new File(config, "moreculling.toml"), FileUtil.loadFromAssetToByte(activity,"moreculling.toml"));
        FileUtils.writeByteArrayToFile(new File(config, "modernfix-mixins.properties"), FileUtil.loadFromAssetToByte(activity,"modernfix-mixins.properties"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir, "options.txt"), FileUtil.loadFromAssetToByte(activity, "options.txt"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir, "servers.dat"), FileUtil.loadFromAssetToByte(activity, "servers.dat"));
        FileUtils.writeByteArrayToFile(new File(config, "vivecraft-client-config.json"), FileUtil.loadFromAssetToByte(activity, "vivecraft-client-config.json"));
        installLauncherAssets(activity);
    }

    public static void installLauncherAssets(Activity activity) throws IOException {
        File hacks = new File(Constants.USER_HOME, "hacks");
        FileUtil.ensureDirectory(hacks);
        FileUtils.writeByteArrayToFile(new File(hacks, "ResConfHack.jar"), FileUtil.loadFromAssetToByte(activity, "hacks/ResConfHack.jar"));
        FileUtils.writeByteArrayToFile(new File(hacks, "resolv.conf"), FileUtil.loadFromAssetToByte(activity, "hacks/resolv.conf"));
    }

    public static void installVisor(Activity activity, MinecraftInstances.Instance instance) throws IOException {
        if (!instance.versionName.equals("1.20.1")) {
            throw new IOException("Plinth only supports Minecraft 1.20.1");
        }

        applyQuestDefaults(instance);
        File mods = new File(instance.gameDir, "mods");
        mods.mkdirs();
        installImmediatelyFast(mods);
        File[] existing = mods.listFiles();
        if (existing != null) {
            for (File file : existing) {
                String name = file.getName().toLowerCase();
                if (name.contains("vivecraft")
                        || name.equals("visor.jar")
                        || name.equals("visoressentials.jar")
                        || name.startsWith("holocraftmr")
                        || name.equals("plinth.jar")
                        || name.contains("moreculling")
                        || name.contains("noisium")
                        || name.contains("badoptimizations")
                        || name.contains("chunky")
                        || name.contains("c2me")
                        || name.contains("voicechat")
                        || name.contains("voxy")) {
                    file.delete();
                }
            }
        }

        FileUtils.writeByteArrayToFile(
                new File(mods, "Visor.jar"),
                FileUtil.loadFromAssetToByte(activity, "mods/Visor.jar")
        );
        FileUtils.writeByteArrayToFile(
                new File(mods, "VisorEssentials.jar"),
                FileUtil.loadFromAssetToByte(activity, "mods/VisorEssentials.jar")
        );
        byte[] addon = FileUtil.loadFromAssetToByte(activity, "mods/Plinth.jar");
        if (addon != null) {
            FileUtils.writeByteArrayToFile(new File(mods, "Plinth.jar"), addon);
        }
        FileUtils.writeByteArrayToFile(
                new File(mods, "MoreCulling.jar"),
                FileUtil.loadFromAssetToByte(activity, "mods/MoreCulling.jar")
        );
        FileUtils.writeByteArrayToFile(
                new File(mods, "Noisium.jar"),
                FileUtil.loadFromAssetToByte(activity, "mods/Noisium.jar")
        );
        FileUtils.writeByteArrayToFile(
                new File(mods, "BadOptimizations.jar"),
                FileUtil.loadFromAssetToByte(activity, "mods/BadOptimizations.jar")
        );
        FileUtils.writeByteArrayToFile(
                new File(mods, "Chunky.jar"),
                FileUtil.loadFromAssetToByte(activity, "mods/Chunky.jar")
        );
        ArrayList<ProjectInfo> projects = new ArrayList<>();
        if (instance.extProjects != null) {
            projects.addAll(Arrays.asList(instance.extProjects));
        }
        projects.removeIf(project -> project.slug.equalsIgnoreCase("Vivecraft")
                || project.slug.equalsIgnoreCase("Visor")
                || project.slug.equalsIgnoreCase("VisorEssentials")
                || project.slug.equalsIgnoreCase("MoreCulling")
                || project.slug.equalsIgnoreCase("Noisium")
                || project.slug.equalsIgnoreCase("BadOptimizations")
                || project.slug.equalsIgnoreCase("Chunky")
                || project.slug.equalsIgnoreCase("C2ME")
                || project.slug.equalsIgnoreCase("Simple-Voice-Chat")
                || project.slug.equalsIgnoreCase("Voxy"));

        ProjectInfo visor = new ProjectInfo();
        visor.slug = "Visor";
        visor.version = "0.6.0-beta";
        visor.download_link = "bundled://visor";
        projects.add(visor);

        ProjectInfo essentials = new ProjectInfo();
        essentials.slug = "VisorEssentials";
        essentials.version = "0.4.0";
        essentials.download_link = "bundled://visor-essentials";
        projects.add(essentials);

        ProjectInfo moreCulling = new ProjectInfo();
        moreCulling.slug = "MoreCulling";
        moreCulling.version = "1.20.1-0.24.5";
        moreCulling.download_link = "bundled://more-culling";
        moreCulling.type = "mod";
        projects.add(moreCulling);

        ProjectInfo noisium = new ProjectInfo();
        noisium.slug = "Noisium";
        noisium.version = "2.3.0+mc1.20-1.20.1";
        noisium.download_link = "bundled://noisium";
        noisium.type = "mod";
        projects.add(noisium);

        ProjectInfo badOptimizations = new ProjectInfo();
        badOptimizations.slug = "BadOptimizations";
        badOptimizations.version = "2.4.1";
        badOptimizations.download_link = "bundled://bad-optimizations";
        badOptimizations.type = "mod";
        projects.add(badOptimizations);

        ProjectInfo chunky = new ProjectInfo();
        chunky.slug = "Chunky";
        chunky.version = "1.3.146";
        chunky.download_link = "bundled://chunky";
        chunky.type = "mod";
        projects.add(chunky);

        instance.extProjects = projects.toArray(new ProjectInfo[0]);
    }

    private static void applyQuestDefaults(MinecraftInstances.Instance instance) throws IOException {
        File marker = new File(instance.gameDir, ".qcx-render-distance-4");
        if (marker.isFile()) {
            return;
        }

        File options = new File(instance.gameDir, "options.txt");
        if (options.isFile()) {
            List<String> lines = Files.readAllLines(options.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("renderDistance:")) {
                    lines.set(i, "renderDistance:4");
                } else if (line.startsWith("simulationDistance:")) {
                    lines.set(i, "simulationDistance:4");
                }
            }
            Files.write(options.toPath(), lines, StandardCharsets.UTF_8);
        }
        marker.createNewFile();
    }

    private static void installImmediatelyFast(File mods) throws IOException {
        File target = new File(mods, "ImmediatelyFast.jar");
        if (target.isFile() && DownloadUtils.compareSHA1(target, IMMEDIATELY_FAST_SHA1)) {
            return;
        }

        File update = new File(mods, "ImmediatelyFast.jar.update");
        FileUtils.deleteQuietly(update);
        try {
            DownloadUtils.downloadFile(IMMEDIATELY_FAST_URL, update, 348818);
            if (!DownloadUtils.compareSHA1(update, IMMEDIATELY_FAST_SHA1)) {
                throw new IOException("ImmediatelyFast download hash mismatch");
            }
            Files.move(update.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            FileUtils.deleteQuietly(update);
        }
    }

    public static class AsyncDownload implements Runnable {
        private final Map.Entry<String, JsonElement> entry;
        private final String gameDir;
        private final String fileName;

        public AsyncDownload(Map.Entry<String, JsonElement> entry, String gameDir) {
            this.entry = entry;
            this.gameDir = gameDir;
            this.fileName = entry.getKey();
        }

        @Override
        public void run() {
            VersionInfo.Asset asset = new Gson().fromJson(entry.getValue(), VersionInfo.Asset.class);
            String path = asset.hash.substring(0, 2) + "/" + asset.hash;
            File assetFile = new File(gameDir + "/assets/objects/", path);

            for (int i = 0; i < 5; i++) {
                if (i == 4) throw new RuntimeException(String.format("Asset download of %s failed after 5 retries", fileName));

                if (!assetFile.exists()) {
                    Logger.getInstance().appendToLog("Downloading: " + fileName);
                    try {
                        DownloadUtils.downloadFile(Constants.MOJANG_RESOURCES_URL + "/" + path, assetFile, 0);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                if (DownloadUtils.compareSHA1(assetFile, asset.hash)) {
                    break;
                } else {
                    assetFile.delete();
                }
            }
        }
    }


    //Used for mod libraries, vanilla is handled a different (tbh better) way
    private static String parseLibraryNameToPath(String libraryName) {
        String[] parts = libraryName.split(":");
        String location = parts[0].replace(".", "/");
        String name = parts[1];
        String version = parts[2];

        return String.format("%s/%s/%s/%s", location, name, version, name + "-" + version + ".jar");
    }
}
