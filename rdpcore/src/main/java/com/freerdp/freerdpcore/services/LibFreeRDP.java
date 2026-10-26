/*
 * Thin Java binding for FreeRDP's Android JNI library (libfreerdp-android).
 *
 * The native library looks this class up by its fully qualified name, exports
 * the freerdp_* natives against it, and calls the static On* methods by name
 * and signature. Package, class name, native signatures and callback
 * signatures must therefore match FreeRDP's own LibFreeRDP.java
 * (client/Android/Studio/freeRDPCore, Apache License 2.0). Everything else
 * here is specific to rdp-mobile.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

public final class LibFreeRDP
{
	private static final String TAG = "LibFreeRDP";

	/** Return values for certificate callbacks, as defined by FreeRDP. */
	public static final int CERT_REJECT = 0;
	public static final int CERT_ACCEPT_SESSION = 2;

	/** Pointer event flags (MS-RDPBCGR 2.2.8.1.1.3.1.1.3). */
	public static final int PTR_FLAGS_HWHEEL = 0x0400;
	public static final int PTR_FLAGS_WHEEL = 0x0200;
	public static final int PTR_FLAGS_WHEEL_NEGATIVE = 0x0100;
	public static final int PTR_FLAGS_MOVE = 0x0800;
	public static final int PTR_FLAGS_DOWN = 0x8000;
	public static final int PTR_FLAGS_BUTTON1 = 0x1000;
	public static final int PTR_FLAGS_BUTTON2 = 0x2000;
	public static final int PTR_FLAGS_BUTTON3 = 0x4000;
	public static final int WHEEL_DELTA = 0x0078;

	/** Callbacks for one FreeRDP instance. Called on FreeRDP's native thread. */
	public interface Listener
	{
		void onPreConnect();
		void onConnectionSuccess();
		void onConnectionFailure();
		void onDisconnecting();
		void onDisconnected();
		void onSettingsChanged(int width, int height, int bpp);
		boolean onAuthenticate(StringBuilder username, StringBuilder domain,
		                       StringBuilder password);
		int onVerifyCertificate(String host, long port, String commonName, String subject,
		                        String issuer, String fingerprint, long flags);
		void onGraphicsUpdate(int x, int y, int width, int height);
		void onGraphicsResize(int width, int height, int bpp);
		void onRemoteClipboardChanged(String data);
		void onPointerSet(int[] pixels, int width, int height, int hotX, int hotY);
		void onPointerSetNull();
		void onPointerSetDefault();
	}

	private static final ConcurrentHashMap<Long, Listener> listeners = new ConcurrentHashMap<>();

	static
	{
		System.loadLibrary("freerdp-android");
		String major = freerdp_get_jni_version().split("[.-]")[0];
		System.loadLibrary("freerdp-client" + major);
		System.loadLibrary("freerdp" + major);
		System.loadLibrary("winpr" + major);
	}

	/** The native side instantiates this class once to hold a global reference. */
	public LibFreeRDP()
	{
	}

	public static String version()
	{
		return freerdp_get_version();
	}

	public static long newInstance(Context context, Listener listener)
	{
		long inst = freerdp_new(context);
		if (inst != 0)
			listeners.put(inst, listener);
		return inst;
	}

	/** Frees the instance. Call only after onDisconnected or onConnectionFailure. */
	public static void freeInstance(long inst)
	{
		listeners.remove(inst);
		freerdp_free(inst);
	}

	public static boolean parseArguments(long inst, String[] args)
	{
		return freerdp_parse_arguments(inst, args);
	}

	/** Starts the connection on a native thread and returns at once. */
	public static boolean connect(long inst)
	{
		return freerdp_connect(inst);
	}

	public static boolean disconnect(long inst)
	{
		return freerdp_disconnect(inst);
	}

	public static String lastError(long inst)
	{
		return freerdp_get_last_error_string(inst);
	}

	public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int w, int h)
	{
		return freerdp_update_graphics(inst, bitmap, x, y, w, h);
	}

	public static boolean sendCursorEvent(long inst, int x, int y, int flags)
	{
		return freerdp_send_cursor_event(inst, x, y, flags);
	}

	/** keycode is a Windows virtual-key code (VK_*). */
	public static boolean sendKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_key_event(inst, keycode, down);
	}

	public static boolean sendUnicodeKeyEvent(long inst, int codepoint, boolean down)
	{
		return freerdp_send_unicodekey_event(inst, codepoint, down);
	}

	public static boolean sendClipboardData(long inst, String data)
	{
		return freerdp_send_clipboard_data(inst, data);
	}

	public static boolean sendMonitorLayout(long inst, int width, int height)
	{
		return freerdp_send_monitor_layout(inst, width, height);
	}

	// ---- natives (signatures fixed by libfreerdp-android) ----

	private static native boolean freerdp_has_h264();
	private static native boolean freerdp_has_camera_redirection();
	private static native String freerdp_get_jni_version();
	private static native String freerdp_get_version();
	private static native String freerdp_get_build_revision();
	private static native String freerdp_get_build_config();
	private static native long freerdp_new(Context context);
	private static native void freerdp_free(long inst);
	private static native boolean freerdp_parse_arguments(long inst, String[] args);
	private static native boolean freerdp_connect(long inst);
	private static native boolean freerdp_disconnect(long inst);
	private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
	                                                      int width, int height);
	private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);
	private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);
	private static native boolean freerdp_send_unicodekey_event(long inst, int keycode,
	                                                            boolean down);
	private static native boolean freerdp_is_unicode_input_supported(long inst);
	private static native boolean freerdp_send_clipboard_data(long inst, String data);
	private static native boolean freerdp_send_clipboard_image_data(long inst, byte[] data,
	                                                                String mimeType);
	private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);
	private static native String freerdp_get_last_error_string(long inst);

	// ---- callbacks from native code (names and signatures fixed) ----

	private static Listener l(long inst)
	{
		Listener l = listeners.get(inst);
		if (l == null)
			Log.w(TAG, "callback for unknown instance");
		return l;
	}

	private static void OnPreConnect(long inst)
	{
		Listener l = l(inst);
		if (l != null)
			l.onPreConnect();
	}

	private static void OnConnectionSuccess(long inst)
	{
		Listener l = l(inst);
		if (l != null)
			l.onConnectionSuccess();
	}

	private static void OnConnectionFailure(long inst)
	{
		Listener l = l(inst);
		if (l != null)
			l.onConnectionFailure();
	}

	private static void OnDisconnecting(long inst)
	{
		Listener l = l(inst);
		if (l != null)
			l.onDisconnecting();
	}

	private static void OnDisconnected(long inst)
	{
		Listener l = l(inst);
		if (l != null)
			l.onDisconnected();
	}

	private static void OnSettingsChanged(long inst, int width, int height, int bpp)
	{
		Listener l = l(inst);
		if (l != null)
			l.onSettingsChanged(width, height, bpp);
	}

	private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
	                                      StringBuilder password)
	{
		Listener l = l(inst);
		return l != null && l.onAuthenticate(username, domain, password);
	}

	/** Gateways are not supported; refusing ends the connection attempt. */
	private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
	                                             StringBuilder domain, StringBuilder password)
	{
		return false;
	}

	private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
	                                         String subject, String issuer, String fingerprint,
	                                         long flags)
	{
		Listener l = l(inst);
		if (l == null)
			return CERT_REJECT;
		return l.onVerifyCertificate(host, port, commonName, subject, issuer, fingerprint, flags);
	}

	/**
	 * FreeRDP calls this when its own known_hosts store has a different
	 * certificate. The app never stores certificates there (it answers
	 * CERT_ACCEPT_SESSION), so the pin check in the listener decides.
	 */
	private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
	                                                String commonName, String subject,
	                                                String issuer, String fingerprint,
	                                                String oldSubject, String oldIssuer,
	                                                String oldFingerprint, long flags)
	{
		return OnVerifyCertificateEx(inst, host, port, commonName, subject, issuer, fingerprint,
		                             flags);
	}

	/** RemoteApp and camera redirection stay off. */
	private static boolean OnExperimentalFeature(long inst, int feature)
	{
		return false;
	}

	private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height)
	{
		Listener l = l(inst);
		if (l != null)
			l.onGraphicsUpdate(x, y, width, height);
	}

	private static void OnGraphicsResize(long inst, int width, int height, int bpp)
	{
		Listener l = l(inst);
		if (l != null)
			l.onGraphicsResize(width, height, bpp);
	}

	private static void OnRemoteClipboardChanged(long inst, String data)
	{
		Listener l = l(inst);
		if (l != null)
			l.onRemoteClipboardChanged(data);
	}

	private static void OnRemoteClipboardImageChanged(long inst, byte[] data)
	{
	}

	private static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX,
	                                 int hotY)
	{
		Listener l = l(inst);
		if (l != null)
			l.onPointerSet(pixels, width, height, hotX, hotY);
	}

	private static void OnPointerSetNull(long inst)
	{
		Listener l = l(inst);
		if (l != null)
			l.onPointerSetNull();
	}

	private static void OnPointerSetDefault(long inst)
	{
		Listener l = l(inst);
		if (l != null)
			l.onPointerSetDefault();
	}

	// RemoteApp (RAIL) callbacks. The app never passes /app, so these do not
	// fire, but the native helper does not clear a NoSuchMethodError, so they
	// exist as no-ops.

	private static void OnRailWindowUpdate(long inst, long windowId, int width, int height,
	                                       int[] pixels)
	{
	}

	private static void OnRailWindowMove(long inst, long windowId, int x, int y, int w, int h)
	{
	}

	private static void OnRailWindowHide(long inst, long windowId)
	{
	}

	private static void OnRailWindowDestroy(long inst, long windowId)
	{
	}

	private static void OnRailSessionEnd(long inst)
	{
	}

	private static void OnRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId)
	{
	}
}
