// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterShellArgs;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.renderer.OnFirstFrameRenderedListener;
import io.flutter.plugin.platform.PlatformPlugin;
import io.flutter.view.FlutterMain;

/**
 * {@code Activity} which displays a fullscreen Flutter UI.
 * <p>
 * WARNING: THIS CLASS IS EXPERIMENTAL. DO NOT SHIP A DEPENDENCY ON THIS CODE.
 * IF YOU USE IT, WE WILL BREAK YOU.
 * <p>
 * {@code FlutterActivity} is the simplest and most direct way to integrate Flutter within an
 * Android app.
 * <p>
 * The Dart entrypoint executed within this {@code Activity} is "main()" by default. The entrypoint
 * may be specified explicitly by passing the name of the entrypoint method as a {@code String} in
 * {@link #EXTRA_DART_ENTRYPOINT}, e.g., "myEntrypoint".
 * <p>
 * The Flutter route that is initially loaded within this {@code Activity} is "/". The initial
 * route may be specified explicitly by passing the name of the route as a {@code String} in
 * {@link #EXTRA_INITIAL_ROUTE}, e.g., "my/deep/link".
 * <p>
 * The app bundle path, Dart entrypoint, and initial route can each be controlled in a subclass of
 * {@code FlutterActivity} by overriding their respective methods:
 * <ul>
 *   <li>{@link #getAppBundlePath()}</li>
 *   <li>{@link #getDartEntrypoint()}</li>
 *   <li>{@link #getInitialRoute()}</li>
 * </ul>
 * If Flutter is needed in a location that cannot use an {@code Activity}, consider using
 * a {@link FlutterFragment}. Using a {@link FlutterFragment} requires forwarding some calls from
 * an {@code Activity} to the {@link FlutterFragment}.
 * <p>
 * If Flutter is needed in a location that can only use a {@code View}, consider using a
 * {@link FlutterView}. Using a {@link FlutterView} requires forwarding some calls from an
 * {@code Activity}, as well as forwarding lifecycle calls from an {@code Activity} or a
 * {@code Fragment}.
 */
// TODO(mattcarroll): explain each call forwarded to Fragment (first requires resolution of PluginRegistry API).
public class FullFlutterActivity extends FragmentActivity implements OnFirstFrameRenderedListener {
  private static final String TAG = "FlutterActivity";

  // Meta-data arguments, processed from manifest XML.
  protected static final String DART_ENTRYPOINT_META_DATA_KEY = "io.flutter.Entrypoint";
  protected static final String INITIAL_ROUTE_META_DATA_KEY = "io.flutter.InitialRoute";

  // Intent extra arguments.
  protected static final String EXTRA_DART_ENTRYPOINT = "dart_entrypoint";
  protected static final String EXTRA_INITIAL_ROUTE = "initial_route";
  protected static final String EXTRA_BACKGROUND_MODE = "background_mode";

  // Default configuration.
  protected static final String DEFAULT_DART_ENTRYPOINT = "main";
  protected static final String DEFAULT_INITIAL_ROUTE = "/";
  protected static final String DEFAULT_BACKGROUND_MODE = BackgroundMode.opaque.name();

  // Used to cover the Activity until the 1st frame is rendered so as to
  // avoid a brief black flicker from a SurfaceView version of FlutterView.
  private View coverView;

  @Nullable
  private FlutterEngine flutterEngine;
  private boolean isFlutterEngineFromActivity;
  @Nullable
  private FlutterView flutterView;
  @Nullable
  private PlatformPlugin platformPlugin;

  /**
   * Creates an {@link Intent} that launches a {@code FlutterActivity}, which executes
   * a {@code main()} Dart entrypoint, and displays the "/" route as Flutter's initial route.
   */
  public static Intent createDefaultIntent(@NonNull Context launchContext) {
    return createBuilder().build(launchContext);
  }

  /**
   * Creates an {@link IntentBuilder}, which can be used to configure an {@link Intent} to
   * launch a {@code FlutterActivity}.
   */
  public static IntentBuilder createBuilder() {
    return new IntentBuilder(FlutterActivity.class);
  }

  /**
   * Builder to create an {@code Intent} that launches a {@code FlutterActivity} with the
   * desired configuration.
   */
  public static class IntentBuilder {
    private final Class<? extends FlutterActivity> activityClass;
    private String dartEntrypoint = DEFAULT_DART_ENTRYPOINT;
    private String initialRoute = DEFAULT_INITIAL_ROUTE;
    private String backgroundMode = DEFAULT_BACKGROUND_MODE;

    protected IntentBuilder(@NonNull Class<? extends FlutterActivity> activityClass) {
      this.activityClass = activityClass;
    }

    /**
     * The name of the initial Dart method to invoke, defaults to "main".
     */
    @NonNull
    public IntentBuilder dartEntrypoint(@NonNull String dartEntrypoint) {
      this.dartEntrypoint = dartEntrypoint;
      return this;
    }

    /**
     * The initial route that a Flutter app will render in this {@link FlutterFragment},
     * defaults to "/".
     */
    @NonNull
    public IntentBuilder initialRoute(@NonNull String initialRoute) {
      this.initialRoute = initialRoute;
      return this;
    }

    /**
     * The mode of {@code FlutterActivity}'s background, either {@link BackgroundMode#opaque} or
     * {@link BackgroundMode#transparent}.
     * <p>
     * The default background mode is {@link BackgroundMode#opaque}.
     * <p>
     * Choosing a background mode of {@link BackgroundMode#transparent} will configure the inner
     * {@link FlutterView} of this {@code FlutterActivity} to be configured with a
     * {@link FlutterTextureView} to support transparency. This choice has a non-trivial performance
     * impact. A transparent background should only be used if it is necessary for the app design
     * being implemented.
     * <p>
     * A {@code FlutterActivity} that is configured with a background mode of
     * {@link BackgroundMode#transparent} must have a theme applied to it that includes the
     * following property: {@code <item name="android:windowIsTranslucent">true</item>}.
     */
    @NonNull
    public IntentBuilder backgroundMode(@NonNull BackgroundMode backgroundMode) {
      this.backgroundMode = backgroundMode.name();
      return this;
    }

    /**
     * Creates and returns an {@link Intent} that will launch a {@code FlutterActivity} with
     * the desired configuration.
     */
    @NonNull
    public Intent build(@NonNull Context context) {
      return new Intent(context, activityClass)
          .putExtra(EXTRA_DART_ENTRYPOINT, dartEntrypoint)
          .putExtra(EXTRA_INITIAL_ROUTE, initialRoute)
          .putExtra(EXTRA_BACKGROUND_MODE, backgroundMode);
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    Log.d(TAG, "onCreate()");
    super.onCreate(savedInstanceState);
    configureWindowForTransparency();
    setContentView(createFlutterView());
    showCoverView();
    configureStatusBarForFullscreenFlutterExperience();
    initFlutter();
  }

  /**
   * Sets this {@code Activity}'s {@code Window} background to be transparent, and hides the status
   * bar, if this {@code Activity}'s desired {@link BackgroundMode} is {@link BackgroundMode#transparent}.
   * <p>
   * For {@code Activity} transparency to work as expected, the theme applied to this {@code Activity}
   * must include {@code <item name="android:windowIsTranslucent">true</item>}.
   */
  private void configureWindowForTransparency() {
    BackgroundMode backgroundMode = getBackgroundMode();
    if (backgroundMode == BackgroundMode.transparent) {
      getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      getWindow().setFlags(
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
      );
    }
  }

  /**
   * Cover all visible {@code Activity} area with a {@code View} that paints everything the same
   * color as the {@code Window}.
   * <p>
   * This cover {@code View} should be displayed at the very beginning of the {@code Activity}'s
   * lifespan and then hidden once Flutter renders its first frame. The purpose of this cover is to
   * cover {@link FlutterSurfaceView}, which briefly displays a black rectangle before it can make
   * itself transparent.
   */
  private void showCoverView() {
    if (getBackgroundMode() == BackgroundMode.transparent) {
      // Don't display an opaque cover view if the Activity is intended to be transparent.
      return;
    }

    // Create the coverView.
    if (coverView == null) {
      coverView = new View(this);
      addContentView(coverView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // Pain the coverView with the Window's background.
    Drawable background = createCoverViewBackground();
    if (background != null) {
      coverView.setBackground(background);
    } else {
      // If we can't obtain a window background to replicate then we'd be guessing as to the least
      // intrusive color. But there is no way to make an accurate guess. In this case we don't
      // give the coverView any color, which means a brief black rectangle will be visible upon
      // Activity launch.
    }
  }

  @Nullable
  private Drawable createCoverViewBackground() {
    TypedValue typedValue = new TypedValue();
    boolean hasBackgroundColor = getTheme().resolveAttribute(
        android.R.attr.windowBackground,
        typedValue,
        true
    );
    if (hasBackgroundColor && typedValue.resourceId != 0) {
      return getResources().getDrawable(typedValue.resourceId, getTheme());
    } else {
      return null;
    }
  }

  /**
   * Hides the cover {@code View}.
   * <p>
   * This method should be called when Flutter renders its first frame. See {@link #showCoverView()}
   * for details.
   */
  private void hideCoverView() {
    if (coverView != null) {
      coverView.setVisibility(View.GONE);
    }
  }

  private void configureStatusBarForFullscreenFlutterExperience() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      Window window = getWindow();
      window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
      window.setStatusBarColor(0x40000000);
      window.getDecorView().setSystemUiVisibility(PlatformPlugin.DEFAULT_SYSTEM_UI);
    }
  }

  @NonNull
  private View createFlutterView() {
    FrameLayout container = new FrameLayout(this);
    container.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    ));

    FlutterView.RenderMode renderMode = FlutterView.RenderMode.surface;
    FlutterView.TransparencyMode transparencyMode = FlutterView.TransparencyMode.opaque;
    if (getBackgroundMode() == BackgroundMode.transparent) {
      renderMode = FlutterView.RenderMode.texture;
      transparencyMode = FlutterView.TransparencyMode.transparent;
    }

    flutterView = new FlutterView(this, renderMode, transparencyMode);
    flutterView.addOnFirstFrameRenderedListener(this);
    container.addView(flutterView);

    return container;
  }

  private void initFlutter() {
    initializeFlutter(getContextCompat());

    // When "retain instance" is true, the FlutterEngine will survive configuration
    // changes. Therefore, we create a new one only if one does not already exist.
    if (flutterEngine == null) {
      setupFlutterEngine();
    }

    // Regardless of whether or not a FlutterEngine already existed, the PlatformPlugin
    // is bound to a specific Activity. Therefore, it needs to be created and configured
    // every time this Fragment attaches to a new Activity.
    // TODO(mattcarroll): the PlatformPlugin needs to be reimagined because it implicitly takes
    //                    control of the entire window. This is unacceptable for non-fullscreen
    //                    use-cases.
    platformPlugin = new PlatformPlugin(this, flutterEngine.getPlatformChannel());

    if (shouldAttachEngineToActivity()) {
      // Notify any plugins that are currently attached to our FlutterEngine that they
      // are now attached to an Activity.
      //
      // Passing this Fragment's Lifecycle should be sufficient because as long as this Fragment
      // is attached to its Activity, the lifecycles should be in sync. Once this Fragment is
      // detached from its Activity, that Activity will be detached from the FlutterEngine, too,
      // which means there shouldn't be any possibility for the Fragment Lifecycle to get out of
      // sync with the Activity. We use the Fragment's Lifecycle because it is possible that the
      // attached Activity is not a LifecycleOwner.
      flutterEngine.getActivityControlSurface().attachToActivity(this, getLifecycle());
    }
  }

  private void initializeFlutter(@NonNull Context context) {
//    String[] flutterShellArgsArray = getArguments().getStringArray(ARG_FLUTTER_INITIALIZATION_ARGS);
    String[] flutterShellArgsArray = null;
    FlutterShellArgs flutterShellArgs = new FlutterShellArgs(
        flutterShellArgsArray != null ? flutterShellArgsArray : new String[] {}
    );

    FlutterMain.ensureInitializationComplete(context.getApplicationContext(), flutterShellArgs.toArray());
  }

  /**
   * Obtains a reference to a FlutterEngine to back this {@code FlutterFragment}.
   * <p>
   * First, {@code FlutterFragment} subclasses are given an opportunity to provide a
   * {@link FlutterEngine} by overriding {@link #createFlutterEngine(Context)}.
   * <p>
   * Second, the {@link FragmentActivity} that owns this {@code FlutterFragment} is
   * given the opportunity to provide a {@link FlutterEngine} as a {@link FlutterFragment.FlutterEngineProvider}.
   * <p>
   * If subclasses do not provide a {@link FlutterEngine}, and the owning {@link FragmentActivity}
   * does not implement {@link FlutterFragment.FlutterEngineProvider} or chooses to return {@code null}, then a new
   * {@link FlutterEngine} is instantiated.
   */
  private void setupFlutterEngine() {
    // First, defer to subclasses for a custom FlutterEngine.
    flutterEngine = createFlutterEngine(getContextCompat());
    if (flutterEngine != null) {
      return;
    }

    // Neither our subclass, nor our owning Activity wanted to provide a custom FlutterEngine.
    // Create a FlutterEngine to back our FlutterView.
    Log.d(TAG, "No subclass or our attached Activity provided a custom FlutterEngine. Creating a "
        + "new FlutterEngine for this FlutterFragment.");
    flutterEngine = new FlutterEngine(this);
    isFlutterEngineFromActivity = false;
  }

  /**
   * Hook for subclasses to return a {@link FlutterEngine} with whatever configuration
   * is desired.
   * <p>
   * This method takes precedence for creation of a {@link FlutterEngine} over any owning
   * {@code Activity} that may implement {@link FlutterFragment.FlutterEngineProvider}.
   * <p>
   * Consider returning a cached {@link FlutterEngine} instance from this method to avoid the
   * typical warm-up time that a new {@link FlutterEngine} instance requires.
   * <p>
   * If null is returned then a new default {@link FlutterEngine} will be created to back this
   * {@code FlutterFragment}.
   */
  @Nullable
  protected FlutterEngine createFlutterEngine(@NonNull Context context) {
    return null;
  }

  @Override
  public void onStart() {
    super.onStart();
    Log.d(TAG, "onStart()");

    // We post() the code that attaches the FlutterEngine to our FlutterView because there is
    // some kind of blocking logic on the native side when the surface is connected. That lag
    // causes launching Activitys to wait a second or two before launching. By post()'ing this
    // behavior we are able to move this blocking logic to after the Activity's launch.
    // TODO(mattcarroll): figure out how to avoid blocking the MAIN thread when connecting a surface
    new Handler().post(new Runnable() {
      @Override
      public void run() {
        flutterView.attachToFlutterEngine(flutterEngine);

        // TODO(mattcarroll): the following call should exist here, but the plugin system needs to be revamped.
        //                    The existing attach() method does not know how to handle this kind of FlutterView.
        //flutterEngine.getPlugins().attach(this, getActivity());

        doInitialFlutterViewRun();
      }
    });
  }

  /**
   * Starts running Dart within the FlutterView for the first time.
   *
   * Reloading/restarting Dart within a given FlutterView is not supported. If this method is
   * invoked while Dart is already executing then it does nothing.
   *
   * {@code flutterEngine} must be non-null when invoking this method.
   */
  private void doInitialFlutterViewRun() {
    if (flutterEngine.getDartExecutor().isExecutingDart()) {
      // No warning is logged because this situation will happen on every config
      // change if the developer does not choose to retain the Fragment instance.
      // So this is expected behavior in many cases.
      return;
    }

    // The engine needs to receive the Flutter app's initial route before executing any
    // Dart code to ensure that the initial route arrives in time to be applied.
    if (getInitialRoute() != null) {
      flutterEngine.getNavigationChannel().setInitialRoute(getInitialRoute());
    }

    // Configure the Dart entrypoint and execute it.
    DartExecutor.DartEntrypoint entrypoint = new DartExecutor.DartEntrypoint(
        getResources().getAssets(),
        getAppBundlePath(),
        getDartEntrypoint()
    );
    flutterEngine.getDartExecutor().executeDartEntrypoint(entrypoint);
  }

  @Override
  public void onResume() {
    super.onResume();
    Log.d(TAG, "onResume()");
    flutterEngine.getLifecycleChannel().appIsResumed();
  }

  @Override
  public void onPostResume() {
    super.onPostResume();
    Log.d(TAG, "onPostResume()");
    if (flutterEngine != null) {
      // TODO(mattcarroll): find a better way to handle the update of UI overlays than calling through
      //                    to platformPlugin. We're implicitly entangling the Window, Activity, Fragment,
      //                    and engine all with this one call.
      platformPlugin.onPostResume();
    } else {
      Log.w(TAG, "onPostResume() invoked before FlutterFragment was attached to an Activity.");
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    Log.d(TAG, "onPause()");
    flutterEngine.getLifecycleChannel().appIsInactive();
  }

  @Override
  public void onStop() {
    super.onStop();
    Log.d(TAG, "onStop()");
    flutterEngine.getLifecycleChannel().appIsPaused();
    flutterView.detachFromFlutterEngine();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    flutterView.removeOnFirstFrameRenderedListener(this);

    if (shouldAttachEngineToActivity()) {
      // Notify plugins that they are no longer attached to an Activity.
      if (isChangingConfigurations()) {
        flutterEngine.getActivityControlSurface().detachFromActivityForConfigChanges();
      } else {
        flutterEngine.getActivityControlSurface().detachFromActivity();
      }
    }

    // Null out the platformPlugin to avoid a possible retain cycle between the plugin, this Fragment,
    // and this Fragment's Activity.
    platformPlugin.destroy();
    platformPlugin = null;

    // Destroy our FlutterEngine if we're not set to retain it.
    if (!retainFlutterEngineAfterFragmentDestruction() && !isFlutterEngineFromActivity) {
      flutterEngine.destroy();
      flutterEngine = null;
    }
  }

  /**
   * Returns true if the {@link FlutterEngine} within this {@code FlutterFragment} should outlive
   * the {@code FlutterFragment}, itself.
   *
   * Defaults to false. This method can be overridden in subclasses to retain the
   * {@link FlutterEngine}.
   */
  // TODO(mattcarroll): consider a dynamic determination of this preference based on whether the
  //                    engine was created automatically, or if the engine was provided manually.
  //                    Manually provided engines should probably not be destroyed.
  protected boolean retainFlutterEngineAfterFragmentDestruction() {
    return false;
  }

  protected boolean shouldAttachEngineToActivity() {
    return true;
  }

  @Override
  protected void onNewIntent(Intent intent) {
    // Forward Intents to our FlutterFragment in case it cares.
    if (flutterEngine != null) {
      flutterEngine.getActivityControlSurface().onNewIntent(intent);
    } else {
      Log.w(TAG, "onNewIntent() invoked before FlutterFragment was attached to an Activity.");
    }
  }

  @Override
  public void onBackPressed() {
    Log.d(TAG, "onBackPressed()");
    if (flutterEngine != null) {
      flutterEngine.getNavigationChannel().popRoute();
    } else {
      Log.w(TAG, "Invoked onBackPressed() before FlutterFragment was attached to an Activity.");
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (flutterEngine != null) {
      flutterEngine.getActivityControlSurface().onRequestPermissionsResult(requestCode, permissions, grantResults);
    } else {
      Log.w(TAG, "onRequestPermissionResult() invoked before FlutterFragment was attached to an Activity.");
    }
  }

  @Override
  public void onUserLeaveHint() {
    if (flutterEngine != null) {
      flutterEngine.getActivityControlSurface().onUserLeaveHint();
    } else {
      Log.w(TAG, "onUserLeaveHint() invoked before FlutterFragment was attached to an Activity.");
    }
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (flutterEngine != null) {
      // Use a trim level delivered while the application is running so the
      // framework has a chance to react to the notification.
      if (level == TRIM_MEMORY_RUNNING_LOW) {
        flutterEngine.getSystemChannel().sendMemoryPressureWarning();
      }
    } else {
      Log.w(TAG, "onTrimMemory() invoked before FlutterFragment was attached to an Activity.");
    }
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (flutterEngine != null) {
      flutterEngine.getActivityControlSurface().onActivityResult(requestCode, resultCode, data);
    } else {
      Log.w(TAG, "onActivityResult() invoked before FlutterFragment was attached to an Activity.");
    }
  }

  /**
   * Callback invoked when memory is low.
   *
   * This implementation forwards a memory pressure warning to the running Flutter app.
   */
  @Override
  public void onLowMemory() {
    super.onLowMemory();
    flutterEngine.getSystemChannel().sendMemoryPressureWarning();
  }

  @SuppressWarnings("unused")
  @Nullable
  protected FlutterEngine getFlutterEngine() {
    return flutterEngine;
  }

  /**
   * The path to the bundle that contains this Flutter app's resources, e.g., Dart code snapshots.
   * <p>
   * When this {@code FlutterActivity} is run by Flutter tooling and a data String is included
   * in the launching {@code Intent}, that data String is interpreted as an app bundle path.
   * <p>
   * By default, the app bundle path is obtained from {@link FlutterMain#findAppBundlePath(Context)}.
   * <p>
   * Subclasses may override this method to return a custom app bundle path.
   */
  @NonNull
  protected String getAppBundlePath() {
    // If this Activity was launched from tooling, and the incoming Intent contains
    // a custom app bundle path, return that path.
    // TODO(mattcarroll): determine if we should have an explicit FlutterTestActivity instead of conflating.
    if (isDebuggable() && Intent.ACTION_RUN.equals(getIntent().getAction())) {
      String appBundlePath = getIntent().getDataString();
      if (appBundlePath != null) {
        return appBundlePath;
      }
    }

    // Return the default app bundle path.
    // TODO(mattcarroll): move app bundle resolution into an appropriately named class.
    return FlutterMain.findAppBundlePath(getApplicationContext());
  }

  /**
   * The Dart entrypoint that will be executed as soon as the Dart snapshot is loaded.
   * <p>
   * This preference can be controlled with 2 methods:
   * <ol>
   *   <li>Pass a {@code String} as {@link #EXTRA_DART_ENTRYPOINT} with the launching {@code Intent}, or</li>
   *   <li>Set a {@code <meta-data>} called {@link #DART_ENTRYPOINT_META_DATA_KEY} for this
   *       {@code Activity} in the Android manifest.</li>
   * </ol>
   * If both preferences are set, the {@code Intent} preference takes priority.
   * <p>
   * The reason that a {@code <meta-data>} preference is supported is because this {@code Activity}
   * might be the very first {@code Activity} launched, which means the developer won't have
   * control over the incoming {@code Intent}.
   * <p>
   * Subclasses may override this method to directly control the Dart entrypoint.
   */
  @NonNull
  protected String getDartEntrypoint() {
    if (getIntent().hasExtra(EXTRA_DART_ENTRYPOINT)) {
      return getIntent().getStringExtra(EXTRA_DART_ENTRYPOINT);
    }

    try {
      ActivityInfo activityInfo = getPackageManager().getActivityInfo(
          getComponentName(),
          PackageManager.GET_META_DATA|PackageManager.GET_ACTIVITIES
      );
      Bundle metadata = activityInfo.metaData;
      String desiredDartEntrypoint = metadata != null ? metadata.getString(DART_ENTRYPOINT_META_DATA_KEY) : null;
      return desiredDartEntrypoint != null ? desiredDartEntrypoint : DEFAULT_DART_ENTRYPOINT;
    } catch (PackageManager.NameNotFoundException e) {
      return DEFAULT_DART_ENTRYPOINT;
    }
  }

  /**
   * The initial route that a Flutter app will render upon loading and executing its Dart code.
   * <p>
   * This preference can be controlled with 2 methods:
   * <ol>
   *   <li>Pass a boolean as {@link #EXTRA_INITIAL_ROUTE} with the launching {@code Intent}, or</li>
   *   <li>Set a {@code <meta-data>} called {@link #INITIAL_ROUTE_META_DATA_KEY} for this
   *    {@code Activity} in the Android manifest.</li>
   * </ol>
   * If both preferences are set, the {@code Intent} preference takes priority.
   * <p>
   * The reason that a {@code <meta-data>} preference is supported is because this {@code Activity}
   * might be the very first {@code Activity} launched, which means the developer won't have
   * control over the incoming {@code Intent}.
   * <p>
   * Subclasses may override this method to directly control the initial route.
   */
  @NonNull
  protected String getInitialRoute() {
    if (getIntent().hasExtra(EXTRA_INITIAL_ROUTE)) {
      return getIntent().getStringExtra(EXTRA_INITIAL_ROUTE);
    }

    try {
      ActivityInfo activityInfo = getPackageManager().getActivityInfo(
          getComponentName(),
          PackageManager.GET_META_DATA|PackageManager.GET_ACTIVITIES
      );
      Bundle metadata = activityInfo.metaData;
      String desiredInitialRoute = metadata != null ? metadata.getString(INITIAL_ROUTE_META_DATA_KEY) : null;
      return desiredInitialRoute != null ? desiredInitialRoute : DEFAULT_INITIAL_ROUTE;
    } catch (PackageManager.NameNotFoundException e) {
      return DEFAULT_INITIAL_ROUTE;
    }
  }

  /**
   * The desired window background mode of this {@code Activity}, which defaults to
   * {@link BackgroundMode#opaque}.
   */
  @NonNull
  protected BackgroundMode getBackgroundMode() {
    if (getIntent().hasExtra(EXTRA_BACKGROUND_MODE)) {
      return BackgroundMode.valueOf(getIntent().getStringExtra(EXTRA_BACKGROUND_MODE));
    } else {
      return BackgroundMode.opaque;
    }
  }

  /**
   * Returns true if Flutter is running in "debug mode", and false otherwise.
   * <p>
   * Debug mode allows Flutter to operate with hot reload and hot restart. Release mode does not.
   */
  private boolean isDebuggable() {
    return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
  }

  @Override
  public void onFirstFrameRendered() {
    hideCoverView();
  }

  @NonNull
  private Context getContextCompat() {
    return this;
  }

  /**
   * The mode of the background of a {@code FlutterActivity}, either opaque or transparent.
   */
  public enum BackgroundMode {
    /** Indicates a FlutterActivity with an opaque background. This is the default. */
    opaque,
    /** Indicates a FlutterActivity with a transparent background. */
    transparent
  }
}
