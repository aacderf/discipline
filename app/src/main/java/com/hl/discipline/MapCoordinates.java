package com.hl.discipline;

import android.content.Context;
import com.amap.api.maps.CoordinateConverter;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.LatLng;

/** Convert platform GPS coordinates only at the map and geofence boundary. */
final class MapCoordinates {
  private MapCoordinates() {}

  static LatLng gpsToMap(Context context, double lat, double lng) {
    if (!context.getSharedPreferences("PlaceActivity", Context.MODE_PRIVATE)
        .getBoolean("mapPrivacyAccepted", false) && !PositionSource.consented(context))
      throw new IllegalStateException("尚未同意地图或定位隐私说明");
    MapsInitializer.updatePrivacyShow(context, true, true);
    MapsInitializer.updatePrivacyAgree(context, true);
    return new CoordinateConverter(context)
        .from(CoordinateConverter.CoordType.GPS)
        .coord(new LatLng(lat, lng))
        .convert();
  }
}
