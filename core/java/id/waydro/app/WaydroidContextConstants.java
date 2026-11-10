/**
 * Copyright (C) 2015, The CyanogenMod Project
 *               2017-2023 The LineageOS Project
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

package id.waydro.app;

import android.annotation.SdkConstant;

/**
 * @hide
 * TODO: We need to somehow make these managers accessible via getSystemService
 */
public final class WaydroidContextConstants {

    /**
     * @hide
     */
    private WaydroidContextConstants() {
        // Empty constructor
    }

    /**
     * Manages waydroid platform
     *
     * @hide
     */
    public static final String WAYDROID_PLATFORM_SERVICE = "waydroidplatform";

    /**
     * Manages waydroid hardware
     *
     * @hide
     */
    public static final String WAYDROID_HARDWARE_SERVICE = "waydroidhardware";

    /**
     * Monitors waydroid user
     *
     * @hide
     */
    public static final String WAYDROID_USERMONITOR_SERVICE = "waydroidusermonitor";

    /**
     * Forwards notifications to the desktop
     *
     * @hide
     */
    public static final String WAYDROID_NOTIFICATIONS_SERVICE = "waydroidnotifications";

    /**
     * Shows media sessions in the desktop's media controls
     *
     * @hide
     */
    public static final String WAYDROID_MEDIA_PLAYERS_SERVICE = "waydroidmediaplayers";
}
