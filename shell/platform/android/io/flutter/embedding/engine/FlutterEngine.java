// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine;

import android.support.annotation.NonNull;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.renderer.FlutterRenderer;

/**
 * A single Flutter execution environment.
 * <p>
 * WARNING: THIS CLASS IS EXPERIMENTAL. DO NOT SHIP A DEPENDENCY ON THIS CODE.
 * IF YOU USE IT, WE WILL BREAK YOU.
 * <p>
 * A {@code FlutterEngine} can execute in the background, or it can be rendered to the screen by
 * using the accompanying {@link FlutterRenderer}.  Rendering can be started and stopped, thus
 * allowing a {@code FlutterEngine} to move from UI interaction to data-only processing and then
 * back to UI interaction.
 * <p>
 * Multiple {@code FlutterEngine}s may exist, execute Dart code, and render UIs within a single
 * Android app.
 * <p>
 * To start running Flutter within this {@code FlutterEngine}, get a reference to this engine's
 * {@link DartExecutor} and then use {@link DartExecutor#executeDartEntrypoint(DartExecutor.DartEntrypoint)}.
 * The {@link DartExecutor#executeDartEntrypoint(DartExecutor.DartEntrypoint)} method has no effect
 * when invoked more than once.
 * <p>
 * To start rendering Flutter content to the screen, use {@link #getRenderer()} to obtain a
 * {@link FlutterRenderer} and then attach a {@link FlutterRenderer.RenderSurface}.  Consider using
 * a {@link io.flutter.embedding.android.FlutterView} as a {@link FlutterRenderer.RenderSurface}.
 */
public class FlutterEngine {
  private static final String TAG = "FlutterEngine";

  private final FlutterJNI flutterJNI;
  private final FlutterRenderer renderer;
  private final DartExecutor dartExecutor;

  public FlutterEngine() {
    this.flutterJNI = new FlutterJNI();
    this.dartExecutor = new DartExecutor(flutterJNI);
    this.renderer = new FlutterRenderer(flutterJNI);
    
    attachToJni();
  }

  private void attachToJni() {
    // TODO(mattcarroll): update native call to not take in "isBackgroundView"
    flutterJNI.attachToNative(false);
    dartExecutor.onAttachedToJNI();
  }

  // TODO(mattcarroll): refactor FlutterEngine to manage all JNI behaviors without exposing publicly.
  public void detachFromJni() {
    dartExecutor.onDetachedFromJNI();
    // TODO(mattcarroll): investigate the concept of "detachment" and remove this method if detachment doesn't need to exist.
    flutterJNI.detachFromNativeButKeepNativeResources();
  }

  public void destroy() {
    flutterJNI.detachFromNativeAndReleaseResources();
  }

  @NonNull
  public DartExecutor getDartExecutor() {
    return dartExecutor;
  }

  @NonNull
  public FlutterRenderer getRenderer() {
    return renderer;
  }

  /**
   * Lifecycle callbacks for Flutter engine lifecycle events.
   */
  public interface EngineLifecycleListener {
    /**
     * Lifecycle callback invoked before a hot restart of the Flutter engine.
     */
    void onPreEngineRestart();
  }
}
