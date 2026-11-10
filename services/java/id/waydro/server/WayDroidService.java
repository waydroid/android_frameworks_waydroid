/*
 * Copyright (C) 2021 The WayDroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package id.waydro.server;

import android.app.Notification;
import android.app.PendingIntent;
import android.annotation.NonNull;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.media.session.MediaSession;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.net.Uri;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;

import com.android.server.SystemService;

import id.waydro.app.WaydroidContextConstants;
import id.waydro.waydroid.AppInfo;
import id.waydro.waydroid.IHardware;
import id.waydro.waydroid.Hardware;
import id.waydro.waydroid.IPlatform;
import id.waydro.waydroid.Platform;
import id.waydro.waydroid.IUserMonitor;
import id.waydro.waydroid.UserMonitor;
import id.waydro.waydroid.IMediaPlayers;
import id.waydro.waydroid.INotifications;
import id.waydro.waydroid.Notifications;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

import libcore.io.IoUtils;

/** @hide **/
public class WayDroidService extends SystemService {
    private static final String TAG = "WayDroidService";
    private static final String BROADCAST_ACTION_INSTALL =
            "id.waydro.waydroid.ACTION_INSTALL_COMMIT";
    private static final String BROADCAST_ACTION_UNINSTALL =
            "id.waydro.waydroid.ACTION_UNINSTALL_COMMIT";
    private static final String ICONS_DIR = "/data/icons";

    private Context mContext;
    private PackageManager mPm = null;
    private UserMonitor mUM = null;
    private Hardware mWaydroidHardware = null;
    private Notifications mWaydroidNotifications = null;
    private NotificationListenerService mSystemNotificationListener = null;

    // Map android notification id -> host notification id
    private Map<String, Integer> mNotificationIdMap = new HashMap<String, Integer>();

    // Map (host notification id, host action id) -> PendingIntent
    private Map<Integer, Map<String, PendingIntent>> mNotificationActionsMap = new HashMap<Integer, Map<String, PendingIntent>>();

    private IMediaPlayers mWaydroidMediaPlayers = null;
    private WayDroidMediaSessions mMediaSessions = null;

    public WayDroidService(Context context) {
        super(context);
        mContext = context;
        if (context != null) {
            mPm = context.getPackageManager();
        } else {
            Log.w(TAG, "No context available");
        }
    }

    @Override
    public void onStart() {
        publishBinderService(WaydroidContextConstants.WAYDROID_PLATFORM_SERVICE, mPlatformService);
        if (mContext != null) {
            mUM = UserMonitor.getInstance(mContext);
            mWaydroidHardware = Hardware.getInstance(mContext);
            try {
                mWaydroidNotifications = Notifications.getInstance(mContext);
            } catch (Exception e) {
                Log.w(TAG, e.getMessage());
            }
        } else {
            Log.w(TAG, "No context available");
        }
        if (mUM != null) {
            registerPackageMonitor();
        }
        if (mWaydroidNotifications != null) {
            registerNotificationListener();
        }
        if (mWaydroidHardware != null) {
            registerShutdownHandler();
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_SYSTEM_SERVICES_READY || mContext == null)
            return;
        // The host adds its service after it starts Android, so look it up here rather than in onStart
        mWaydroidMediaPlayers = IMediaPlayers.Stub.asInterface(
                ServiceManager.getService(WaydroidContextConstants.WAYDROID_MEDIA_PLAYERS_SERVICE));
        if (mWaydroidMediaPlayers != null) {
            registerMediaSessions();
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        List<ApplicationInfo> apps = mPm.getInstalledApplications(0);
        for (int n = 0; n < apps.size(); n++) {
            ApplicationInfo appInfo = apps.get(n);

            Intent launchIntent = getAppLaunchIntent(appInfo.packageName);
            if (launchIntent == null) {
                continue;
            }
            saveApplicationIcon(appInfo.packageName);
        }
        if (mUM != null) {
            mUM.userUnlocked(user.getUserIdentifier());
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo defaultLauncher = mPm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (defaultLauncher.activityInfo != null) {
            String nameOfLauncherPkg = defaultLauncher.activityInfo.packageName;
            SystemProperties.set("waydroid.blacklist_apps", nameOfLauncherPkg);
        }
    }

    private Intent getAppLaunchIntent(String packageName) {
        Intent launchIntent = null;

        if (mPm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            launchIntent = mPm.getLeanbackLaunchIntentForPackage(packageName);
        }

        if (launchIntent == null) {
            launchIntent = mPm.getLaunchIntentForPackage(packageName);
        }

        return launchIntent;
    }

    private void saveApplicationIcon(String packageName) {
        Drawable icon = null;
        try {
            icon = mPm.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException ex) {
            return;
        }
        if (icon == null)
            return;

        Bitmap iconBitmap = drawableToBitmap(icon, false);
        File imageFile = new File(ICONS_DIR, packageName + ".png");
        FileOutputStream fileOutStream = null;
        try {
            fileOutStream = new FileOutputStream(imageFile);
            iconBitmap.compress(Bitmap.CompressFormat.PNG, 90, fileOutStream);
            fileOutStream.close();
        } catch (IOException e) {
            Log.e("app", e.getMessage());
            if (fileOutStream != null) {
                try {
                    fileOutStream.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
        imageFile.setReadable(true, false);
        imageFile.setWritable(true, false);
    }

    private Bitmap drawableToBitmap(Drawable drawable, boolean force) {
        if (!force && drawable instanceof BitmapDrawable)
            return ((BitmapDrawable)drawable).getBitmap();

        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    private void registerPackageMonitor() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                if (mUM != null) {
                    mUM.packageStateChanged(UserMonitor.WAYDROID_PACKAGE_ADDED, packageName, uid);
                }
                saveApplicationIcon(packageName);
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                if (mUM != null) {
                    mUM.packageStateChanged(UserMonitor.WAYDROID_PACKAGE_REMOVED, packageName, uid);
                }
                File appIcon = new File(ICONS_DIR + "/" + packageName + ".png");
                if (appIcon.exists())
                    appIcon.delete();
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                if (mUM != null) {
                    mUM.packageStateChanged(UserMonitor.WAYDROID_PACKAGE_UPDATED, packageName, uid);
                }
                saveApplicationIcon(packageName);
            }
        };

        monitor.register(mContext, BackgroundThread.getHandler().getLooper(), UserHandle.ALL, true);
    }

    private static long idCounter = 0;
    public static String nextId() {
        return String.valueOf(idCounter++);
    }

    private void registerNotificationListener() {
        mNotificationIdMap.clear();
        mNotificationActionsMap.clear();
        mSystemNotificationListener = new NotificationListenerService() {
            private INotifications.ImageData getImageData(Notification notification) {
                Icon icon = notification.getLargeIcon();
                if (icon == null) {
                    return null;
                }

                Drawable drawable = icon.loadDrawable(mContext);
                Bitmap bitmap = drawableToBitmap(drawable, true);
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int channels = 4;
                int rowstride = width * channels;
                int pixels[] = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                byte bytes[] = new byte[rowstride * height];
                for (int i = 0; i < pixels.length; i++) {
                    int color = pixels[i];
                    bytes[0 + i * channels] = (byte)((color >> 16) & 0xff); // B
                    bytes[1 + i * channels] = (byte)((color >> 8) & 0xff);  // G
                    bytes[2 + i * channels] = (byte)((color >> 0) & 0xff);  // R
                    bytes[3 + i * channels] = (byte)((color >> 24) & 0xff); // A
                }

                INotifications.ImageData imageData = new INotifications.ImageData();
                imageData.width = width;
                imageData.height = height;
                imageData.rowstride = rowstride;
                imageData.has_alpha = true;
                imageData.data = bytes;
                return imageData;
            }

            @Override
            public void onListenerConnected() {
                Log.i(TAG, "Connected to system notification manager");
            }

            @Override
            public void onNotificationPosted(StatusBarNotification sbn) {
                String key = sbn.getKey();
                String packageName = sbn.getPackageName();
                Notification notification = sbn.getNotification();

                if (notification.isForegroundService()) {
                    return;
                }

                // The desktop shows a media notification as a player in its media controls
                MediaSession.Token token = notification.extras.getParcelable(
                        Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
                if (mMediaSessions != null && mMediaSessions.isShown(token)) {
                    closeHostNotification(key);
                    return;
                }

                String appName = _getAppName(packageName);
                String summary = notification.extras.getString(Notification.EXTRA_TITLE, "");
                String body = notification.extras.getString(Notification.EXTRA_TEXT, "");

                List<INotifications.Action> hostActions = new LinkedList<INotifications.Action>();
                Map<String, PendingIntent> actionMap = new HashMap<String, PendingIntent>();

                if (notification.contentIntent != null) {
                    INotifications.Action defaultAction = new INotifications.Action();
                    defaultAction.id = "default";
                    defaultAction.label = "";
                    hostActions.add(defaultAction);
                    actionMap.put(defaultAction.id, notification.contentIntent);
                }

                if (notification.actions != null) {
                    for (Notification.Action androidAction : notification.actions) {
                        if (androidAction.actionIntent != null) {
                            String label = androidAction.title.toString();
                            String hostActionId = nextId();

                            INotifications.Action hostAction = new INotifications.Action();
                            hostAction.id = hostActionId;
                            hostAction.label = label;

                            hostActions.add(hostAction);
                            actionMap.put(hostActionId, androidAction.actionIntent);
                        }
                    }
                }

                INotifications.ImageData image = getImageData(notification);

                String category = ""; // TODO: try to map Android category to FDO category
                boolean suppressSound = true; // TODO: consider playing the sound on the desktop and silencing android instead
                int expireTimeout = INotifications.TIMEOUT_DEFAULT;
                boolean isResident = true;
                boolean isTransient = false;
                byte urgency = INotifications.Urgency.NORMAL; // TODO: try to map Android importance to FDO urgency

                int existingId = mNotificationIdMap.getOrDefault(key, INotifications.ID_NONE);

                int newId = mWaydroidNotifications.notify(existingId, appName, packageName, summary, body,
                                              hostActions, image, category, suppressSound, expireTimeout,
                                              isResident, isTransient, urgency);
                if (newId == INotifications.ID_NONE) {
                    return;
                }

                mNotificationIdMap.put(key, newId);
                mNotificationActionsMap.put(newId, actionMap);
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn) {
                closeHostNotification(sbn.getKey());
            }
        };

        try {
            mSystemNotificationListener.registerAsSystemService(mContext,
                    new ComponentName(mContext.getPackageName(), getClass().getCanonicalName()),
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to register system notification listener", e);
        }

        mWaydroidNotifications.registerListener(new INotifications.INotificationCallback.Stub() {
            @Override
            public void onActionInvoked(int notificationId, String actionId, String xdgActivationToken) {
                Map<String, PendingIntent> innerMap = mNotificationActionsMap.get(notificationId);
                if (innerMap == null)
                    return;
                PendingIntent intent = innerMap.get(actionId);
                if (intent == null)
                    return;

                try {
                    intent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.i(TAG, "Canceled notification action: " + e.getMessage());
                }

                // TODO: Activate window through hwcomposer
            }
        });
    }

    private void closeHostNotification(String key) {
        Integer id = mNotificationIdMap.get(key);
        mNotificationIdMap.remove(key);
        if (id != null) {
            mNotificationActionsMap.remove(id);
            mWaydroidNotifications.closeNotification(id);
        }
    }

    private void registerMediaSessions() {
        mMediaSessions = new WayDroidMediaSessions(mContext, mWaydroidMediaPlayers,
                new WayDroidMediaSessions.Listener() {
            @Override
            public void onRaise(String packageName) {
                Intent launchIntent = getAppLaunchIntent(packageName);
                if (launchIntent != null)
                    mContext.startActivity(launchIntent);
            }

            @Override
            public String getAppName(String packageName) {
                return _getAppName(packageName);
            }
        });
        mMediaSessions.start();
    }

    private void registerShutdownHandler() {
        IntentFilter filter = new IntentFilter();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Android is shutting down");
                mWaydroidHardware.shutdownRequest(SystemProperties.get("sys.shutdown.requested"));
            }
        };

        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private String _getAppName(String packageName) {
        if (mPm == null || mContext == null)
            return "";

        ApplicationInfo appInfo;
        try {
            appInfo = mPm.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            Log.e(TAG, e.getMessage());
            return "";
        }
        String name = appInfo.name;
        CharSequence label = appInfo.loadLabel(mPm);
        if (label != null)
            name = label.toString();

        return name;
    }

    /* Service */
    private final IBinder mPlatformService = new IPlatform.Stub() {
        @Override
        public String getprop(String prop, String default_value) {
            return SystemProperties.get(prop, default_value);
        }

        @Override
        public void setprop(String prop, String value) {
            SystemProperties.set(prop, value);
        }

        @Override
        public List<AppInfo> getAppsInfo() {
            List<AppInfo> result = new ArrayList<>();

            if (mPm == null)
                return result;

            List<ApplicationInfo> apps = mPm.getInstalledApplications(0);
            for (int n = 0; n < apps.size(); n++) {
                ApplicationInfo appInfo = apps.get(n);

                Intent launchIntent = getAppLaunchIntent(appInfo.packageName);
                if (launchIntent == null) {
                    continue;
                }

                String name = appInfo.name;
                CharSequence label = appInfo.loadLabel(mPm);
                if (label != null)
                    name = label.toString();

                AppInfo info = new AppInfo();
                info.name = name;
                info.packageName = appInfo.packageName;
                info.action = launchIntent.getAction();
                if (launchIntent.getData() != null)
                    info.launchIntent = launchIntent.getData().toString();
                else
                    info.launchIntent = "";

                info.componentClassName = launchIntent.getComponent().getClassName();
                info.componentPackageName = launchIntent.getComponent().getPackageName();
                info.categories = new ArrayList<String>(launchIntent.getCategories());
                result.add(info);
            }
            return result;
        }

        @Override
        public AppInfo getAppInfo(String packageName) {
            if (mPm == null)
                return null;

            ApplicationInfo appInfo;
            try {
                appInfo = mPm.getApplicationInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                return null;
            }
            Intent launchIntent = getAppLaunchIntent(appInfo.packageName);
            if (launchIntent == null) {
                return null;
            }

            String name = appInfo.name;
            CharSequence label = appInfo.loadLabel(mPm);
            if (label != null)
                name = label.toString();

            AppInfo info = new AppInfo();
            info.name = name;
            info.packageName = appInfo.packageName;
            info.action = launchIntent.getAction();
            if (launchIntent.getData() != null)
                info.launchIntent = launchIntent.getData().toString();
            else
                info.launchIntent = "";

            info.componentClassName = launchIntent.getComponent().getClassName();
            info.componentPackageName = launchIntent.getComponent().getPackageName();
            info.categories = new ArrayList<String>(launchIntent.getCategories());
            return info;
        }

        @Override
        public int installApp(String path) {
            int ret = 0;
            final Uri packageURI;

            // Populate apkURI, must be present
            if (path != null) {
                packageURI = Uri.fromFile(new File(path));
            } else {
                return -1;
            }

            final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            final PackageInstaller packageInstaller = mPm.getPackageInstaller();
            PackageInstaller.Session session = null;
            try {
                final int sessionId = packageInstaller.createSession(params);
                final byte[] buffer = new byte[65536];
                session = packageInstaller.openSession(sessionId);
                final InputStream in = mContext.getContentResolver().openInputStream(packageURI);
                final OutputStream out = session.openWrite("PackageInstaller", 0, -1 /* sizeBytes, unknown */);
                try {
                    int c;
                    while ((c = in.read(buffer)) != -1) {
                        out.write(buffer, 0, c);
                    }
                    session.fsync(out);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                }
                // Create a PendingIntent and use it to generate the IntentSender
                Intent broadcastIntent = new Intent(BROADCAST_ACTION_INSTALL);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        mContext,
                        sessionId,
                        broadcastIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                session.commit(pendingIntent.getIntentSender());
            } catch (Exception e) {
                Log.e(TAG, "Failure", e);
                ret = -1;
            } finally {
                IoUtils.closeQuietly(session);
            }

            return ret;
        }

        @Override
        public int removeApp(String packageName) {
          final PackageInstaller packageInstaller = mPm.getPackageInstaller();

          mPm.setInstallerPackageName(packageName, mContext.getPackageName());
          // Create a PendingIntent and use it to generate the IntentSender
          Intent broadcastIntent = new Intent(BROADCAST_ACTION_UNINSTALL);
          PendingIntent pendingIntent = PendingIntent.getBroadcast(
                  mContext, // context
                  0, // arbitary
                  broadcastIntent,
                  PendingIntent.FLAG_UPDATE_CURRENT);
          packageInstaller.uninstall(packageName, pendingIntent.getIntentSender());

          return 0;
        }

        @Override
        public void launchApp(String packageName) {
            if (mPm == null || mContext == null)
                return;

            ApplicationInfo appInfo;
            try {
                appInfo = mPm.getApplicationInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                Log.e(TAG, e.getMessage());
                return;
            }
            Intent launchIntent = getAppLaunchIntent(appInfo.packageName);
            if (launchIntent == null) {
                return;
            }

            mContext.startActivity(launchIntent);
        }

        @Override
        public String launchIntent(String action, String uri) {
            if (mPm == null || mContext == null)
                return "";

            Intent i;
            if (uri == null || uri.isEmpty())
                i = new Intent(action);
            else
                i = new Intent(action, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ResolveInfo ri = mPm.resolveActivity(i, 0);
            try {
                mContext.startActivity(i);
            } catch (ActivityNotFoundException ignored) {}

            if (ri != null) {
                return ri.activityInfo.packageName;
            }
            return "";
        }

        @Override
        public String getAppName(String packageName) {
            return _getAppName(packageName);
        }

        @Override
        public void settingsPutString(int mode, String key, String value) {
            if (mode == Platform.SETTINGS_SECURE) {
                Settings.Secure.putString(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_SYSTEM) {
                Settings.System.putString(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_GLOBAL) {
                Settings.Global.putString(mContext.getContentResolver(), key, value);
            }
        }

        @Override
        public String settingsGetString(int mode, String key) {
            if (mode == Platform.SETTINGS_SECURE) {
                return Settings.Secure.getString(mContext.getContentResolver(), key);
            } else if (mode == Platform.SETTINGS_SYSTEM) {
                return Settings.System.getString(mContext.getContentResolver(), key);
            } else if (mode == Platform.SETTINGS_GLOBAL) {
                return Settings.Global.getString(mContext.getContentResolver(), key);
            }
            return "";
        }

        @Override
        public void settingsPutInt(int mode, String key, int value) {
            if (mode == Platform.SETTINGS_SECURE) {
                Settings.Secure.putInt(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_SYSTEM) {
                Settings.System.putInt(mContext.getContentResolver(), key, value);
            } else if (mode == Platform.SETTINGS_GLOBAL) {
                Settings.Global.putInt(mContext.getContentResolver(), key, value);
            }
        }

        @Override
        public int settingsGetInt(int mode, String key) {
            try {
                if (mode == Platform.SETTINGS_SECURE) {
                    return Settings.Secure.getInt(mContext.getContentResolver(), key);
                } else if (mode == Platform.SETTINGS_SYSTEM) {
                    return Settings.System.getInt(mContext.getContentResolver(), key);
                } else if (mode == Platform.SETTINGS_GLOBAL) {
                    return Settings.Global.getInt(mContext.getContentResolver(), key);
                }
            } catch (Settings.SettingNotFoundException e) {
                Log.e(TAG, e.getMessage());
            }
            return Platform.ERROR_UNDEFINED;
        }
    };
}
