package io.flutter.embedding;

import android.support.annotation.NonNull;
import java.io.File;

public interface AssetLocator {
  /**
   * Returns the file name for the given asset. The returned file name can be used to access the
   * asset in the APK through the {@link android.content.res.AssetManager} API.
   *
   * @param asset the name of the asset. The name can be hierarchical
   * @return the filename to be used with {@link android.content.res.AssetManager}
   */
  @NonNull
  String getLookupKeyForAsset(@NonNull String asset);

  /**
   * Returns the file name for the given asset which originates from the specified packageName. The
   * returned file name can be used to access the asset in the APK through the {@link
   * android.content.res.AssetManager} API.
   *
   * @param asset the name of the asset. The name can be hierarchical
   * @param packageName the name of the package from which the asset originates
   * @return the file name to be used with {@link android.content.res.AssetManager}
   */
  @NonNull
  String getLookupKeyForAsset(@NonNull String asset, @NonNull String packageName);

  /**
   * Returns the full asset path for a given {@code filePath} that is rooted in the
   * assets directory.
   */
  @NonNull
  String fullAssetPathFrom(@NonNull String filePath);

  class Default implements AssetLocator {
    private String flutterAssetsDir = "flutter_assets";

    public Default() {}

    public Default(@NonNull String flutterAssetsDir) {
      this.flutterAssetsDir = flutterAssetsDir;
    }

    @Override
    @NonNull
    public String getLookupKeyForAsset(@NonNull String asset) {
      return fullAssetPathFrom(asset);
    }

    @Override
    @NonNull
    public String getLookupKeyForAsset(@NonNull String asset, @NonNull String packageName) {
      return getLookupKeyForAsset(
          "packages" + File.separator + packageName + File.separator + asset);
    }

    @Override
    @NonNull
    public String fullAssetPathFrom(@NonNull String filePath) {
      return flutterAssetsDir + File.separator + filePath;
    }
  }
}
