package app.pocketoverlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;

/** Connection to the stock Retroid firmware service. */
final class RetroidFirmware {
    private static final String FOREGROUND =
            "dumpsys -t 1 activity activities 2>/dev/null | awk "
            + "'/topResumedActivity=ActivityRecord|mResumedActivity: ActivityRecord/ "
            + "{for(i=1;i<=NF;i++) {token=$i; sub(/[}].*/, \"\", token); "
            + "if(token ~ /^[A-Za-z0-9_.]+\\/[A-Za-z0-9_.$]+$/) {print token; exit}}}'";
    private IBinder service;
    private boolean checkedForService;

    String foregroundActivity() {
        return query(FOREGROUND);
    }

    boolean writeSystemSetting(Context context, String setting, int value) {
        try {
            if (Settings.System.putInt(context.getContentResolver(), setting, value)) return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Some stock settings are writable only through Retroid's service.
        }
        query("settings put system " + setting + " " + value);
        return Settings.System.getInt(context.getContentResolver(), setting, -1) == value;
    }

    String query(String command) {
        if (!checkedForService || (service != null && !service.isBinderAlive())) {
            service = findService();
            checkedForService = true;
        }
        if (service == null) {
            return "";
        }
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeStringArray(new String[]{command, "1"});
            if (service.transact(0, request, reply, 0)) {
                byte[] bytes = reply.createByteArray();
                if (bytes != null) {
                    return new String(bytes, StandardCharsets.UTF_8).trim();
                }
            }
        } catch (RemoteException | RuntimeException ignored) {
            // Other firmware can hide the service or use a different protocol.
        } finally {
            request.recycle();
            reply.recycle();
        }
        return "";
    }

    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
    private static IBinder findService() {
        try {
            Class<?> services = Class.forName("android.os.ServiceManager");
            return (IBinder) services.getDeclaredMethod("getService", String.class)
                    .invoke(null, "PServerBinder");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
