// Copyright 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import io.flutter.embedding.legacy.AccessibilityBridge;
import io.flutter.embedding.legacy.FlutterNativeView;
import io.flutter.embedding.legacy.FlutterPluginRegistry;
import io.flutter.embedding.legacy.TextInputPlugin;
import io.flutter.plugin.common.*;
import io.flutter.plugin.platform.PlatformPlugin;
import io.flutter.view.FlutterRunArguments;
import io.flutter.view.TextureRegistry;
import io.flutter.view.VsyncWaiter;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An Android view containing a Flutter app.
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class FlutterView extends SurfaceView implements
    TextureRegistry,
    AccessibilityManager.AccessibilityStateChangeListener {

  private static final String TAG = "FlutterView";

  // Must match the PointerChange enum in pointer.dart.
  private static final int kPointerChangeCancel = 0;
  private static final int kPointerChangeAdd = 1;
  private static final int kPointerChangeRemove = 2;
  private static final int kPointerChangeHover = 3;
  private static final int kPointerChangeDown = 4;
  private static final int kPointerChangeMove = 5;
  private static final int kPointerChangeUp = 6;

  // Must match the PointerDeviceKind enum in pointer.dart.
  private static final int kPointerDeviceKindTouch = 0;
  private static final int kPointerDeviceKindMouse = 1;
  private static final int kPointerDeviceKindStylus = 2;
  private static final int kPointerDeviceKindInvertedStylus = 3;
  private static final int kPointerDeviceKindUnknown = 4;

  static final class ViewportMetrics {
    float devicePixelRatio = 1.0f;
    int physicalWidth = 0;
    int physicalHeight = 0;
    int physicalPaddingTop = 0;
    int physicalPaddingRight = 0;
    int physicalPaddingBottom = 0;
    int physicalPaddingLeft = 0;
    int physicalViewInsetTop = 0;
    int physicalViewInsetRight = 0;
    int physicalViewInsetBottom = 0;
    int physicalViewInsetLeft = 0;
  }

  private final InputMethodManager mImm;
  private final TextInputPlugin mTextInputPlugin;
  private final SurfaceHolder.Callback mSurfaceCallback;
  private final ViewportMetrics mMetrics;
  private final AccessibilityManager mAccessibilityManager;
  private final MethodChannel mFlutterLocalizationChannel;
  private final MethodChannel mFlutterNavigationChannel;
  private final BasicMessageChannel<Object> mFlutterKeyEventChannel;
  private final BasicMessageChannel<String> mFlutterLifecycleChannel;
  private final BasicMessageChannel<Object> mFlutterSystemChannel;
  private final BasicMessageChannel<Object> mFlutterSettingsChannel;
  private final List<ActivityLifecycleListener> mActivityLifecycleListeners;
  private final List<FirstFrameListener> mFirstFrameListeners;
  private final AtomicLong nextTextureId = new AtomicLong(0L);
  private FlutterNativeView mNativeView;
  private FlutterEngine flutterEngine;
  private final AnimationScaleObserver mAnimationScaleObserver;
  private boolean mIsSoftwareRenderingEnabled = false; // using the software renderer or not
  private InputConnection mLastInputConnection;

  // Accessibility
  private boolean mAccessibilityEnabled = false;
  private boolean mTouchExplorationEnabled = false;
  private int mAccessibilityFeatureFlags = 0;
  private TouchExplorationListener mTouchExplorationListener;

  //------ START VIEW OVERRIDES -----
  public FlutterView(Context context) {
    this(context, null);
  }

  public FlutterView(Context context, AttributeSet attrs) {
    this(context, attrs, null, null);
  }

  public FlutterView(Context context, AttributeSet attrs, FlutterNativeView nativeView, FlutterEngine flutterEngine) {
    super(context, attrs);

    Activity activity = (Activity) getContext();
    if (nativeView == null) {
      mNativeView = new FlutterNativeView(activity.getApplicationContext());
      this.flutterEngine = new FlutterEngine(mNativeView, new FlutterPluginRegistry(mNativeView, getContext()));
    } else {
      mNativeView = nativeView;
      this.flutterEngine = flutterEngine;
    }
    mNativeView.attachViewAndActivity(this, activity);

    mIsSoftwareRenderingEnabled = flutterEngine.nativeGetIsSoftwareRenderingEnabled();
    mAnimationScaleObserver = new AnimationScaleObserver(new Handler());
    mMetrics = new ViewportMetrics();
    mMetrics.devicePixelRatio = context.getResources().getDisplayMetrics().density;
    setFocusable(true);
    setFocusableInTouchMode(true);

    int color = 0xFF000000;
    TypedValue typedValue = new TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
    if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
      color = typedValue.data;
    }
    // TODO(abarth): Consider letting the developer override this color.
    final int backgroundColor = color;

    mSurfaceCallback = new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder) {
        assertFlutterEngineAttached();
        flutterEngine.nativeSurfaceCreated(mNativeView.get(), holder.getSurface(), backgroundColor);
      }

      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        assertFlutterEngineAttached();
        flutterEngine.nativeSurfaceChanged(mNativeView.get(), width, height);
      }

      @Override
      public void surfaceDestroyed(SurfaceHolder holder) {
        assertFlutterEngineAttached();
        flutterEngine.nativeSurfaceDestroyed(mNativeView.get());
      }
    };
    getHolder().addCallback(mSurfaceCallback);

    mAccessibilityManager = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);

    mActivityLifecycleListeners = new ArrayList<>();
    mFirstFrameListeners = new ArrayList<>();

    // Configure the platform plugins and flutter channels.
    mFlutterLocalizationChannel = new MethodChannel(flutterEngine, "flutter/localization", JSONMethodCodec.INSTANCE);
    mFlutterNavigationChannel = new MethodChannel(flutterEngine, "flutter/navigation", JSONMethodCodec.INSTANCE);
    mFlutterKeyEventChannel = new BasicMessageChannel<>(flutterEngine, "flutter/keyevent", JSONMessageCodec.INSTANCE);
    mFlutterLifecycleChannel = new BasicMessageChannel<>(flutterEngine, "flutter/lifecycle", StringCodec.INSTANCE);
    mFlutterSystemChannel = new BasicMessageChannel<>(flutterEngine, "flutter/system", JSONMessageCodec.INSTANCE);
    mFlutterSettingsChannel = new BasicMessageChannel<>(flutterEngine, "flutter/settings", JSONMessageCodec.INSTANCE);

    PlatformPlugin platformPlugin = new PlatformPlugin(activity);
    MethodChannel flutterPlatformChannel = new MethodChannel(flutterEngine, "flutter/platform", JSONMethodCodec.INSTANCE);
    flutterPlatformChannel.setMethodCallHandler(platformPlugin);
    addActivityLifecycleListener(platformPlugin);
    mImm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    mTextInputPlugin = new TextInputPlugin(this);

    setLocale(getResources().getConfiguration().locale);
    setUserSettings();
  }

  public void addActivityLifecycleListener(ActivityLifecycleListener listener) {
    mActivityLifecycleListeners.add(listener);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    mAccessibilityEnabled = mAccessibilityManager.isEnabled();
    mTouchExplorationEnabled = mAccessibilityManager.isTouchExplorationEnabled();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      Uri transitionUri = Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE);
      getContext().getContentResolver().registerContentObserver(transitionUri, false, mAnimationScaleObserver);
    }

    if (mAccessibilityEnabled || mTouchExplorationEnabled) {
      ensureAccessibilityEnabled();
    }
    if (mTouchExplorationEnabled) {
      mAccessibilityFeatureFlags ^= AccessibilityFeature.ACCESSIBLE_NAVIGATION.value;
    }
    // Apply additional accessibility settings
    updateAccessibilityFeatures();
    resetWillNotDraw();
    mAccessibilityManager.addAccessibilityStateChangeListener(this);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      if (mTouchExplorationListener == null) {
        mTouchExplorationListener = new TouchExplorationListener();
      }
      mAccessibilityManager.addTouchExplorationStateChangeListener(mTouchExplorationListener);
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    getContext().getContentResolver().unregisterContentObserver(mAnimationScaleObserver);
    mAccessibilityManager.removeAccessibilityStateChangeListener(this);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      mAccessibilityManager.removeTouchExplorationStateChangeListener(mTouchExplorationListener);
    }
  }

  @Override
  public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    try {
      mLastInputConnection = mTextInputPlugin.createInputConnection(this, outAttrs);
      return mLastInputConnection;
    } catch (JSONException e) {
      Log.e(TAG, "Failed to create input connection", e);
      return null;
    }
  }

  @Override
  protected void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    setLocale(newConfig.locale);
    setUserSettings();
  }

  private void setLocale(Locale locale) {
    mFlutterLocalizationChannel.invokeMethod("setLocale", Arrays.asList(locale.getLanguage(), locale.getCountry()));
  }

  private void setUserSettings() {
    Map<String, Object> message = new HashMap<>();
    message.put("textScaleFactor", getResources().getConfiguration().fontScale);
    message.put("alwaysUse24HourFormat", DateFormat.is24HourFormat(getContext()));
    mFlutterSettingsChannel.send(message);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isFlutterEngineAttached()) {
      return false;
    }

    // TODO(abarth): This version check might not be effective in some
    // versions of Android that statically compile code and will be upset
    // at the lack of |requestUnbufferedDispatch|. Instead, we should factor
    // version-dependent code into separate classes for each supported
    // version and dispatch dynamically.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      requestUnbufferedDispatch(event);
    }

    // These values must match the unpacking code in hooks.dart.
    final int kPointerDataFieldCount = 19;
    final int kBytePerField = 8;

    int pointerCount = event.getPointerCount();

    ByteBuffer packet = ByteBuffer.allocateDirect(pointerCount * kPointerDataFieldCount * kBytePerField);
    packet.order(ByteOrder.LITTLE_ENDIAN);

    int maskedAction = event.getActionMasked();
    // ACTION_UP, ACTION_POINTER_UP, ACTION_DOWN, and ACTION_POINTER_DOWN
    // only apply to a single pointer, other events apply to all pointers.
    if (maskedAction == MotionEvent.ACTION_UP || maskedAction == MotionEvent.ACTION_POINTER_UP
        || maskedAction == MotionEvent.ACTION_DOWN || maskedAction == MotionEvent.ACTION_POINTER_DOWN) {
      addPointerForIndex(event, event.getActionIndex(), packet);
    } else {
      // ACTION_MOVE may not actually mean all pointers have moved
      // but it's the responsibility of a later part of the system to
      // ignore 0-deltas if desired.
      for (int p = 0; p < pointerCount; p++) {
        addPointerForIndex(event, p, packet);
      }
    }

    assert packet.position() % (kPointerDataFieldCount * kBytePerField) == 0;
    flutterEngine.nativeDispatchPointerDataPacket(mNativeView.get(), packet, packet.position());
    return true;
  }

  private void addPointerForIndex(MotionEvent event, int pointerIndex, ByteBuffer packet) {
    int pointerChange = getPointerChangeForAction(event.getActionMasked());
    if (pointerChange == -1) {
      return;
    }

    int pointerKind = getPointerDeviceTypeForToolType(event.getToolType(pointerIndex));

    long timeStamp = event.getEventTime() * 1000; // Convert from milliseconds to microseconds.

    packet.putLong(timeStamp); // time_stamp
    packet.putLong(pointerChange); // change
    packet.putLong(pointerKind); // kind
    packet.putLong(event.getPointerId(pointerIndex)); // device
    packet.putDouble(event.getX(pointerIndex)); // physical_x
    packet.putDouble(event.getY(pointerIndex)); // physical_y

    if (pointerKind == kPointerDeviceKindMouse) {
      packet.putLong(event.getButtonState() & 0x1F); // buttons
    } else if (pointerKind == kPointerDeviceKindStylus) {
      packet.putLong((event.getButtonState() >> 4) & 0xF); // buttons
    } else {
      packet.putLong(0); // buttons
    }

    packet.putLong(0); // obscured

    // TODO(eseidel): Could get the calibrated range if necessary:
    // event.getDevice().getMotionRange(MotionEvent.AXIS_PRESSURE)
    packet.putDouble(event.getPressure(pointerIndex)); // pressure
    packet.putDouble(0.0); // pressure_min
    packet.putDouble(1.0); // pressure_max

    if (pointerKind == kPointerDeviceKindStylus) {
      packet.putDouble(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex)); // distance
      packet.putDouble(0.0); // distance_max
    } else {
      packet.putDouble(0.0); // distance
      packet.putDouble(0.0); // distance_max
    }

    packet.putDouble(event.getToolMajor(pointerIndex)); // radius_major
    packet.putDouble(event.getToolMinor(pointerIndex)); // radius_minor

    packet.putDouble(0.0); // radius_min
    packet.putDouble(0.0); // radius_max

    packet.putDouble(event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)); // orientation

    if (pointerKind == kPointerDeviceKindStylus) {
      packet.putDouble(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)); // tilt
    } else {
      packet.putDouble(0.0); // tilt
    }
  }

  private int getPointerChangeForAction(int maskedAction) {
    // Primary pointer:
    if (maskedAction == MotionEvent.ACTION_DOWN) {
      return kPointerChangeDown;
    }
    if (maskedAction == MotionEvent.ACTION_UP) {
      return kPointerChangeUp;
    }
    // Secondary pointer:
    if (maskedAction == MotionEvent.ACTION_POINTER_DOWN) {
      return kPointerChangeDown;
    }
    if (maskedAction == MotionEvent.ACTION_POINTER_UP) {
      return kPointerChangeUp;
    }
    // All pointers:
    if (maskedAction == MotionEvent.ACTION_MOVE) {
      return kPointerChangeMove;
    }
    if (maskedAction == MotionEvent.ACTION_CANCEL) {
      return kPointerChangeCancel;
    }
    return -1;
  }

  private int getPointerDeviceTypeForToolType(int toolType) {
    switch (toolType) {
      case MotionEvent.TOOL_TYPE_FINGER:
        return kPointerDeviceKindTouch;
      case MotionEvent.TOOL_TYPE_STYLUS:
        return kPointerDeviceKindStylus;
      case MotionEvent.TOOL_TYPE_MOUSE:
        return kPointerDeviceKindMouse;
      case MotionEvent.TOOL_TYPE_ERASER:
        return kPointerDeviceKindInvertedStylus;
      default:
        // MotionEvent.TOOL_TYPE_UNKNOWN will reach here.
        return kPointerDeviceKindUnknown;
    }
  }

  @Override
  public boolean onHoverEvent(MotionEvent event) {
    if (!isFlutterEngineAttached()) {
      return false;
    }

    boolean handled = handleAccessibilityHoverEvent(event);
    if (!handled) {
      // TODO(ianh): Expose hover events to the platform,
      // implementing ADD, REMOVE, etc.
    }
    return handled;
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    mMetrics.physicalWidth = width;
    mMetrics.physicalHeight = height;
    updateViewportMetrics();
    super.onSizeChanged(width, height, oldWidth, oldHeight);
  }

  // TODO(mattcarroll): window insets are API 20. what should we do for lower APIs?
  @SuppressLint("NewApi")
  @Override
  public final WindowInsets onApplyWindowInsets(WindowInsets insets) {
    // Status bar, left/right system insets partially obscure content (padding).
    mMetrics.physicalPaddingTop = insets.getSystemWindowInsetTop();
    mMetrics.physicalPaddingRight = insets.getSystemWindowInsetRight();
    mMetrics.physicalPaddingBottom = 0;
    mMetrics.physicalPaddingLeft = insets.getSystemWindowInsetLeft();

    // Bottom system inset (keyboard) should adjust scrollable bottom edge (inset).
    mMetrics.physicalViewInsetTop = 0;
    mMetrics.physicalViewInsetRight = 0;
    mMetrics.physicalViewInsetBottom = insets.getSystemWindowInsetBottom();
    mMetrics.physicalViewInsetLeft = 0;
    updateViewportMetrics();
    return super.onApplyWindowInsets(insets);
  }

  @Override
  @SuppressWarnings("deprecation")
  protected boolean fitSystemWindows(Rect insets) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
      // Status bar, left/right system insets partially obscure content (padding).
      mMetrics.physicalPaddingTop = insets.top;
      mMetrics.physicalPaddingRight = insets.right;
      mMetrics.physicalPaddingBottom = 0;
      mMetrics.physicalPaddingLeft = insets.left;

      // Bottom system inset (keyboard) should adjust scrollable bottom edge (inset).
      mMetrics.physicalViewInsetTop = 0;
      mMetrics.physicalViewInsetRight = 0;
      mMetrics.physicalViewInsetBottom = insets.bottom;
      mMetrics.physicalViewInsetLeft = 0;
      updateViewportMetrics();
      return true;
    } else {
      return super.fitSystemWindows(insets);
    }
  }

  private void updateViewportMetrics() {
    if (!isFlutterEngineAttached())
      return;

    flutterEngine.nativeSetViewportMetrics(
      mNativeView.get(),
      mMetrics.devicePixelRatio,
      mMetrics.physicalWidth,
      mMetrics.physicalHeight,
      mMetrics.physicalPaddingTop,
      mMetrics.physicalPaddingRight,
      mMetrics.physicalPaddingBottom,
      mMetrics.physicalPaddingLeft,
      mMetrics.physicalViewInsetTop,
      mMetrics.physicalViewInsetRight,
      mMetrics.physicalViewInsetBottom,
      mMetrics.physicalViewInsetLeft
    );

    WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
    float fps = wm.getDefaultDisplay().getRefreshRate();
    VsyncWaiter.refreshPeriodNanos = (long) (1000000000.0 / fps);
  }

  // TODO(mattcarroll): detachFromGLContext requires API 16. what should we do for earlier APIs?
  @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
  @Override
  public SurfaceTextureEntry createSurfaceTexture() {
    final SurfaceTexture surfaceTexture = new SurfaceTexture(0);
    surfaceTexture.detachFromGLContext();
    final SurfaceTextureRegistryEntry entry = new SurfaceTextureRegistryEntry(nextTextureId.getAndIncrement(),
        surfaceTexture);
    flutterEngine.nativeRegisterTexture(mNativeView.get(), entry.id(), surfaceTexture);
    return entry;
  }

  final class SurfaceTextureRegistryEntry implements TextureRegistry.SurfaceTextureEntry {
    private final long id;
    private final SurfaceTexture surfaceTexture;
    private boolean released;

    SurfaceTextureRegistryEntry(long id, SurfaceTexture surfaceTexture) {
      this.id = id;
      this.surfaceTexture = surfaceTexture;
      this.surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
        @Override
        public void onFrameAvailable(SurfaceTexture texture) {
          flutterEngine.nativeMarkTextureFrameAvailable(mNativeView.get(), SurfaceTextureRegistryEntry.this.id);
        }
      });
    }

    @Override
    public SurfaceTexture surfaceTexture() {
      return surfaceTexture;
    }

    @Override
    public long id() {
      return id;
    }

    @Override
    public void release() {
      if (released) {
        return;
      }
      released = true;
      flutterEngine.nativeUnregisterTexture(mNativeView.get(), id);
      surfaceTexture.release();
    }
  }
  //------ END VIEW OVERRIDES ----

  //----- START KEYEVENT.CALLBACK -----
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (!isFlutterEngineAttached()) {
      return super.onKeyUp(keyCode, event);
    }

    Map<String, Object> message = new HashMap<>();
    message.put("type", "keyup");
    message.put("keymap", "android");
    encodeKeyEvent(event, message);
    mFlutterKeyEventChannel.send(message);
    return super.onKeyUp(keyCode, event);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (!isFlutterEngineAttached()) {
      return super.onKeyDown(keyCode, event);
    }

    if (event.getDeviceId() != KeyCharacterMap.VIRTUAL_KEYBOARD) {
      if (mLastInputConnection != null && mImm.isAcceptingText()) {
        mLastInputConnection.sendKeyEvent(event);
      }
    }

    Map<String, Object> message = new HashMap<>();
    message.put("type", "keydown");
    message.put("keymap", "android");
    encodeKeyEvent(event, message);
    mFlutterKeyEventChannel.send(message);
    return super.onKeyDown(keyCode, event);
  }

  private void encodeKeyEvent(KeyEvent event, Map<String, Object> message) {
    message.put("flags", event.getFlags());
    message.put("codePoint", event.getUnicodeChar());
    message.put("keyCode", event.getKeyCode());
    message.put("scanCode", event.getScanCode());
    message.put("metaState", event.getMetaState());
  }
  //----- END KEYEVENT.CALLBACK -----

  public FlutterEngine getFlutterEngine() {
    return flutterEngine;
  }

  //----- START METHODS INVOKED BY FRAGMENT -----
  public FlutterNativeView getFlutterNativeView() {
    return mNativeView;
  }

  public FlutterPluginRegistry getPluginRegistry() {
    return mNativeView.getPluginRegistry();
  }
  //----- END METHODS INVOKED BY FRAGMENT ---

  //------ START CALLS FORWARDED FROM FRAGMENT ----
  public void onStart() {
    mFlutterLifecycleChannel.send("AppLifecycleState.inactive");
  }

  public void onPause() {
    mFlutterLifecycleChannel.send("AppLifecycleState.inactive");
  }

  public void onPostResume() {
    updateAccessibilityFeatures();
    for (ActivityLifecycleListener listener : mActivityLifecycleListeners) {
      listener.onPostResume();
    }
    mFlutterLifecycleChannel.send("AppLifecycleState.resumed");
  }

  public void onStop() {
    mFlutterLifecycleChannel.send("AppLifecycleState.paused");
  }

  public FlutterNativeView detach() {
    if (!isFlutterEngineAttached())
      return null;
    getHolder().removeCallback(mSurfaceCallback);
    mNativeView.detach();

    FlutterNativeView view = mNativeView;
    mNativeView = null;
    return view;
  }

  public void destroy() {
    if (!isFlutterEngineAttached())
      return;

    getHolder().removeCallback(mSurfaceCallback);

    mNativeView.destroy();
    mNativeView = null;
  }

  public void onMemoryPressure() {
    Map<String, Object> message = new HashMap<>(1);
    message.put("type", "memoryPressure");
    mFlutterSystemChannel.send(message);
  }

  public void setInitialRoute(String route) {
    mFlutterNavigationChannel.invokeMethod("setInitialRoute", route);
  }

  public void pushRoute(String route) {
    mFlutterNavigationChannel.invokeMethod("pushRoute", route);
  }

  public void popRoute() {
    mFlutterNavigationChannel.invokeMethod("popRoute", null);
  }
  //------ END CALLS FORWARDED FROM FRAGMENT ----

  //------- START ACCESSIBILITY ------
  // Called by native to update the semantics/accessibility tree.
  @SuppressWarnings("unused")
  public void updateSemantics(ByteBuffer buffer, String[] strings) {
    try {
      if (mAccessibilityNodeProvider != null) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        mAccessibilityNodeProvider.updateSemantics(buffer, strings);
      }
    } catch (Exception ex) {
      Log.e(TAG, "Uncaught exception while updating semantics", ex);
    }
  }

  public void updateCustomAccessibilityActions(ByteBuffer buffer, String[] strings) {
    try {
      if (mAccessibilityNodeProvider != null) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        mAccessibilityNodeProvider.updateCustomAccessibilityActions(buffer, strings);
      }
    } catch (Exception ex) {
      Log.e(TAG, "Uncaught exception while updating local context actions", ex);
    }
  }

  public void dispatchSemanticsAction(int id, AccessibilityBridge.Action action) {
    dispatchSemanticsAction(id, action, null);
  }

  public void dispatchSemanticsAction(int id, AccessibilityBridge.Action action, Object args) {
    if (!isFlutterEngineAttached())
      return;
    ByteBuffer encodedArgs = null;
    int position = 0;
    if (args != null) {
      encodedArgs = StandardMessageCodec.INSTANCE.encodeMessage(args);
      position = encodedArgs.position();
    }
    flutterEngine.nativeDispatchSemanticsAction(mNativeView.get(), id, action.value, encodedArgs, position);
  }

  private void updateAccessibilityFeatures() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      String transitionAnimationScale = Settings.Global.getString(getContext().getContentResolver(),
          Settings.Global.TRANSITION_ANIMATION_SCALE);
      if (transitionAnimationScale != null && transitionAnimationScale.equals("0")) {
        mAccessibilityFeatureFlags ^= AccessibilityFeature.DISABLE_ANIMATIONS.value;
      } else {
        mAccessibilityFeatureFlags &= ~AccessibilityFeature.DISABLE_ANIMATIONS.value;
      }
    }
    flutterEngine.nativeSetAccessibilityFeatures(mNativeView.get(), mAccessibilityFeatureFlags);
  }

  private void resetWillNotDraw() {
    if (!mIsSoftwareRenderingEnabled) {
      setWillNotDraw(!(mAccessibilityEnabled || mTouchExplorationEnabled));
    } else {
      setWillNotDraw(false);
    }
  }

  @Override
  public void onAccessibilityStateChanged(boolean enabled) {
    if (enabled) {
      ensureAccessibilityEnabled();
    } else {
      mAccessibilityEnabled = false;
      if (mAccessibilityNodeProvider != null) {
        mAccessibilityNodeProvider.setAccessibilityEnabled(false);
      }
      flutterEngine.nativeSetSemanticsEnabled(mNativeView.get(), false);
    }
    resetWillNotDraw();
  }

  /// Must match the enum defined in window.dart.
  private enum AccessibilityFeature {
    ACCESSIBLE_NAVIGATION(1 << 0),
    INVERT_COLORS(1 << 1), // NOT SUPPORTED
    DISABLE_ANIMATIONS(1 << 2);

    AccessibilityFeature(int value) {
      this.value = value;
    }

    final int value;
  }

  // Listens to the global TRANSITION_ANIMATION_SCALE property and notifies us so
  // that we can disable animations in Flutter.
  private class AnimationScaleObserver extends ContentObserver {
    public AnimationScaleObserver(Handler handler) {
      super(handler);
    }

    @Override
    public void onChange(boolean selfChange) {
      this.onChange(selfChange, null);
    }

    // TODO(mattcarroll): getString() requires API 17. what should we do for earlier APIs?
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onChange(boolean selfChange, Uri uri) {
      String value = Settings.Global.getString(getContext().getContentResolver(),
          Settings.Global.TRANSITION_ANIMATION_SCALE);
      if (value == "0") {
        mAccessibilityFeatureFlags ^= AccessibilityFeature.DISABLE_ANIMATIONS.value;
      } else {
        mAccessibilityFeatureFlags &= ~AccessibilityFeature.DISABLE_ANIMATIONS.value;
      }
      flutterEngine.nativeSetAccessibilityFeatures(mNativeView.get(), mAccessibilityFeatureFlags);
    }
  }

  // TODO: TouchExplorationStateChangeListener requires API 19. What do we do about earlier APIs?
  @SuppressLint("NewApi")
  class TouchExplorationListener implements AccessibilityManager.TouchExplorationStateChangeListener {
    @Override
    public void onTouchExplorationStateChanged(boolean enabled) {
      if (enabled) {
        mTouchExplorationEnabled = true;
        ensureAccessibilityEnabled();
        mAccessibilityFeatureFlags ^= AccessibilityFeature.ACCESSIBLE_NAVIGATION.value;
        flutterEngine.nativeSetAccessibilityFeatures(mNativeView.get(), mAccessibilityFeatureFlags);
      } else {
        mTouchExplorationEnabled = false;
        if (mAccessibilityNodeProvider != null) {
          mAccessibilityNodeProvider.handleTouchExplorationExit();
        }
        mAccessibilityFeatureFlags &= ~AccessibilityFeature.ACCESSIBLE_NAVIGATION.value;
        flutterEngine.nativeSetAccessibilityFeatures(mNativeView.get(), mAccessibilityFeatureFlags);
      }
      resetWillNotDraw();
    }
  }

  @Override
  public AccessibilityNodeProvider getAccessibilityNodeProvider() {
    if (mAccessibilityEnabled)
      return mAccessibilityNodeProvider;
    // TODO(goderbauer): when a11y is off this should return a one-off snapshot of
    // the a11y
    // tree.
    return null;
  }

  private AccessibilityBridge mAccessibilityNodeProvider;

  void ensureAccessibilityEnabled() {
    if (!isFlutterEngineAttached())
      return;
    mAccessibilityEnabled = true;
    if (mAccessibilityNodeProvider == null) {
      mAccessibilityNodeProvider = new AccessibilityBridge(this);
    }
    flutterEngine.nativeSetSemanticsEnabled(mNativeView.get(), true);
    mAccessibilityNodeProvider.setAccessibilityEnabled(true);
  }

  void resetAccessibilityTree() {
    if (mAccessibilityNodeProvider != null) {
      mAccessibilityNodeProvider.reset();
    }
  }

  private boolean handleAccessibilityHoverEvent(MotionEvent event) {
    if (!mTouchExplorationEnabled) {
      return false;
    }
    if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER || event.getAction() == MotionEvent.ACTION_HOVER_MOVE) {
      mAccessibilityNodeProvider.handleTouchExploration(event.getX(), event.getY());
    } else if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
      mAccessibilityNodeProvider.handleTouchExplorationExit();
    } else {
      Log.d("flutter", "unexpected accessibility hover event: " + event);
      return false;
    }
    return true;
  }
  //------- END ACCESSIBILITY ----

  //------ START RUN FROM BUNDLE -----
  public void runFromBundle(FlutterRunArguments args) {
    assertFlutterEngineAttached();
    onFlutterEngineWillStart();
    mNativeView.runFromBundle(args);
    onFlutterEngineDidStart();
  }

  /**
   * @deprecated
   * Please use runFromBundle with `FlutterRunArguments`.
   */
  @Deprecated
  public void runFromBundle(String bundlePath, String defaultPath) {
    runFromBundle(bundlePath, defaultPath, "main", false);
  }

  /**
   * @deprecated
   * Please use runFromBundle with `FlutterRunArguments`.
   */
  @Deprecated
  public void runFromBundle(String bundlePath, String defaultPath, String entrypoint) {
    runFromBundle(bundlePath, defaultPath, entrypoint, false);
  }

  /**
   * @deprecated
   * Please use runFromBundle with `FlutterRunArguments`.
   * Parameter `reuseRuntimeController` has no effect.
   */
  @Deprecated
  public void runFromBundle(String bundlePath, String defaultPath, String entrypoint, boolean reuseRuntimeController) {
    FlutterRunArguments args = new FlutterRunArguments();
    args.bundlePath = bundlePath;
    args.entrypoint = entrypoint;
    args.defaultPath = defaultPath;
    runFromBundle(args);
  }

  private boolean isFlutterEngineAttached() {
    return mNativeView != null && mNativeView.isAttached();
  }

  void assertFlutterEngineAttached() {
    if (!isFlutterEngineAttached())
      throw new AssertionError("Platform view is not attached");
  }

  private void onFlutterEngineWillStart() {
    resetAccessibilityTree();
  }

  private void onFlutterEngineDidStart() {
  }
  //------ END RUN FROM BUNDLE ----

  //------ START ENGINE INTERACTIONS -----
  // Called by native to notify first Flutter frame rendered.
  @SuppressWarnings("unused")
  public void onFirstFrame() {
    // Allow listeners to remove themselves when they are called.
    List<FirstFrameListener> listeners = new ArrayList<>(mFirstFrameListeners);
    for (FirstFrameListener listener : listeners) {
      listener.onFirstFrame();
    }
  }

  /**
   * Provide a listener that will be called once when the FlutterView renders its
   * first frame to the underlaying SurfaceView.
   */
  public void addFirstFrameListener(FirstFrameListener listener) {
    mFirstFrameListeners.add(listener);
  }

  /**
   * Remove an existing first frame listener.
   */
  public void removeFirstFrameListener(FirstFrameListener listener) {
    mFirstFrameListeners.remove(listener);
  }

  /**
   * Listener will be called on the Android UI thread once when Flutter renders
   * the first frame.
   */
  public interface FirstFrameListener {
    void onFirstFrame();
  }
  //------ END ENGINE INTERACTIONS ----
}
