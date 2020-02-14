package io.flutter.embedding;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.VisibleForTesting;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.platform.PlatformViewsController;

/**
 * The global Flutter environment on Android.
 *
 * <p>{@code FlutterEnvironment} is responsible for Flutter behaviors that do not pertain to any
 * single {@code FlutterEngine} or Dart isolate.
 */
public class FlutterEnvironment {

  private static FlutterLoader.Factory customFlutterLoaderFactory;
  private static FlutterJNI.Factory customFlutterJNIFactory;
  private static FlutterEnvironment instance;

  /**
   * Configures {@code FlutterEnvironment} to use custom factories to create {@link FlutterLoader}s
   * and {@code FlutterJNI}s, for testing purposes.
   *
   * <p>This method must be invoked before any calls to {@link #getInstance()}. The first call to
   * {@link #getInstance()} after invoking this method will configure the singleton {@code
   * FlutterEnvironment} to use the given factories for any Flutter loading and execution.
   */
  @VisibleForTesting
  public static void configure(
      @Nullable FlutterLoader.Factory flutterLoaderFactory,
      @Nullable FlutterJNI.Factory flutterJNIFactory) {
    if (instance != null) {
      throw new IllegalStateException(
          "Cannot configure() FlutterEnvironment because its singleton already exists. Invoke "
              + "configure() before getInstance().");
    }

    customFlutterLoaderFactory = flutterLoaderFactory;
    customFlutterJNIFactory = flutterJNIFactory;
  }

  /**
   * Returns a {@code FlutterEnvironment} singleton.
   *
   * <p>Creates a new {@code FlutterEnvironment} if one does not yet exist.
   */
  @NonNull
  public static FlutterEnvironment getInstance() {
    if (instance == null) {
      instance = new FlutterEnvironment(customFlutterLoaderFactory, customFlutterJNIFactory);
    }
    return instance;
  }

  @Nullable private final FlutterLoader.Factory flutterLoaderFactory;
  @Nullable private final FlutterJNI.Factory flutterJNIFactory;

  private FlutterEnvironment(
      @Nullable FlutterLoader.Factory flutterLoaderFactory,
      @Nullable FlutterJNI.Factory flutterJNIFactory) {
    this.flutterLoaderFactory = flutterLoaderFactory;
    this.flutterJNIFactory = flutterJNIFactory;
  }

  /**
   * Creates a new {@link FlutterEngine} that immediately executes a given {@code dartCallback}.
   *
   * <p>This method must be invoked on the main thread.
   */
  @NonNull
  @UiThread
  public FlutterEngine executeDartCallbackInFlutterEngine(
      @NonNull Context context, @Nullable String[] dartVmArgs, DartCallback dartCallback) {
    FlutterLoader flutterLoader =
        flutterLoaderFactory != null
            ? flutterLoaderFactory.createFlutterLoader()
            : new FlutterLoader();

    FlutterJNI flutterJNI =
        flutterJNIFactory != null ? flutterJNIFactory.createFlutterJNI() : new FlutterJNI();

    DartExecutor dartExecutor = executeDartCallback(flutterLoader, flutterJNI, context, dartVmArgs, dartCallback);

    return new FlutterEngine(
        context,
        dartExecutor,
        flutterLoader.getAssetLocator(),
        new PlatformViewsController(),
        true);
  }

  /**
   * Creates a new {@link DartExecutor}, runs the given {@code dartCallback}, and returns the new
   * {@link DartExecutor}.
   *
   * <p>This method must be invoked on the main thread.
   *
   * <p>Executing a {@link DartCallback} requires that the Dart VM be loaded because the Dart VM
   * contains the cache where the callback is listed. If this method is invoked before the Dart VM
   * is loaded then this method will initialize the Dart VM before executing the Dart callback. In
   * this case, this method may require a non-trivial execution time.
   */
  @NonNull
  @UiThread
  public DartExecutor executeDartCallback(
      @NonNull Context context, @Nullable String[] dartVmArgs, DartCallback dartCallback) {
    FlutterLoader flutterLoader =
        flutterLoaderFactory != null
            ? flutterLoaderFactory.createFlutterLoader()
            : new FlutterLoader();

    FlutterJNI flutterJNI =
        flutterJNIFactory != null ? flutterJNIFactory.createFlutterJNI() : new FlutterJNI();

    return executeDartCallback(flutterLoader, flutterJNI, context, dartVmArgs, dartCallback);
  }

  @NonNull
  @UiThread
  private DartExecutor executeDartCallback(
      @NonNull FlutterLoader flutterLoader,
      @NonNull FlutterJNI flutterJNI,
      @NonNull Context context,
      @Nullable String[] dartVmArgs,
      DartCallback dartCallback) {
    // Ensure that the Dart VM is loaded and running because if the Dart VM
    // isn't loaded then the callback cache does not exist yet.
    flutterLoader.startInitialization(context.getApplicationContext());
    flutterLoader.ensureInitializationComplete(context, dartVmArgs);

    // Create a DartExecutor
    flutterJNI.attachToNative();
    DartExecutor dartExecutor = new DartExecutor(flutterJNI, context.getAssets());

    // Execute the background callback
    dartExecutor.executeDartCallback(
        new DartCallback(
            context.getAssets(), dartCallback.pathToBundle, dartCallback.callbackHandle));

    // Return the DartExecutor that is running the background callback.
    return dartExecutor;
  }
}
