package com.paltech.salahos;

import android.app.admin.DeviceAdminReceiver;

/**
 * What lets Salah OS be this frame's device owner.
 *
 * A frame is an appliance: one app, for good. Android's own name for the app that runs an
 * appliance is the device owner, and it is the supported way for that app to do what an ordinary
 * app may not - join a Wi-Fi network by itself, among other things. It is given once, when the
 * frame is prepared, with `dpm set-device-owner com.paltech.salahos/.Admin`.
 *
 * Nothing happens here. Being the owner is a standing the app holds, not something it reacts to.
 */
public class Admin extends DeviceAdminReceiver { }
