package pojlib.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import pojlib.input.AWTInputBridge;

public class Clipboard {
    public static volatile ClipboardManager GLOBAL_CLIPBOARD;

    public static void init(Context context) {
        GLOBAL_CLIPBOARD = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    // called from awt_bridge.c
    public static void querySystemClipboard() {
        ClipData clipData = GLOBAL_CLIPBOARD.getPrimaryClip();
        if(clipData == null) {
            AWTInputBridge.nativeClipboardReceived(null, null);
            return;
        }
        ClipData.Item firstClipItem = clipData.getItemAt(0);
        //TODO: coerce to HTML if the clip item is styled
        CharSequence clipItemText = firstClipItem.getText();
        if(clipItemText == null) {
            AWTInputBridge.nativeClipboardReceived(null, null);
            return;
        }
        AWTInputBridge.nativeClipboardReceived(clipItemText.toString(), "plain");
    }

    // called from awt_bridge.c
    public static void putClipboardData(String data, String mimeType) {
        ClipData clipData = null;
        switch(mimeType) {
            case "text/plain":
                clipData = ClipData.newPlainText("AWT Paste", data);
                break;
            case "text/html":
                clipData = ClipData.newHtmlText("AWT Paste", data, data);
        }
        if(clipData != null) GLOBAL_CLIPBOARD.setPrimaryClip(clipData);
    }
}
