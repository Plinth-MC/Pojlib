package pojlib.util;

import android.app.Activity;

import java.io.File;
import java.io.IOException;

public class LWJGLInstaller {
    public static String installLWJGL(Activity activity) throws IOException {
        Logger.getInstance().appendToLog("Checking LWJGL");
        File lwjgl = new File(Constants.USER_HOME + "/lwjgl3/lwjgl-glfw-classes.jar");
        String lwjglAsset = "lwjgl/lwjgl-glfw-classes.jar";

        if (!FileUtil.matchingAssetFile(activity, lwjglAsset, lwjgl)) {
            FileUtil.copyAssetToFile(activity, lwjglAsset, lwjgl);
        }

        Logger.getInstance().appendToLog("LWJGL installed");
        return lwjgl.getAbsolutePath();
    }
}
