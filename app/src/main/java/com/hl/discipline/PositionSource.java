package com.hl.discipline;

import android.content.Context;
import com.amap.api.location.*;

/** One SDK configuration for monitoring and map recentering; consent is never inferred from a key. */
final class PositionSource {
  static boolean consented(Context context) {
    return BuildConfig.AMAP_CONFIGURED && context.getSharedPreferences("PlaceActivity", 0).getBoolean("locationPrivacyAccepted", false);
  }

  static AMapLocationClient start(Context context, boolean once, AMapLocationListener listener)
      throws Exception {
    if (!consented(context)) throw new IllegalStateException("尚未同意融合定位说明");
    AMapLocationClient.updatePrivacyShow(context, true, true);
    AMapLocationClient.updatePrivacyAgree(context, true);
    AMapLocationClientOption.setLocationProtocol(AMapLocationClientOption.AMapLocationProtocol.HTTPS);
    AMapLocationClient client = new AMapLocationClient(context.getApplicationContext());
    AMapLocationClientOption options = new AMapLocationClientOption()
        .setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
        .setInterval(10_000).setLocationCacheEnable(false).setNeedAddress(false)
        .setMockEnable(false).setGpsFirst(false).setHttpTimeOut(12_000)
        .setOnceLocation(once).setOnceLocationLatest(once);
    client.setLocationOption(options);
    client.setLocationListener(listener);
    client.startLocation();
    return client;
  }

  static void close(AMapLocationClient client) {
    if (client != null) { client.stopLocation(); client.onDestroy(); }
  }
}
