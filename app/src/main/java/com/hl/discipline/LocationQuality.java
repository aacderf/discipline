package com.hl.discipline;

/** Selects observations, not an invented or averaged position. All coordinates must share a CRS. */
final class LocationQuality {
  enum Decision { ACCEPT, IGNORE, SUSPECT }
  static final long MAX_AGE = 20_000, PREFER_RECENT = 20_000;
  private long acceptedAt, pendingAt;
  private double latitude, longitude, precision, pendingLat, pendingLng;

  Decision consider(long now, long sample, double lat, double lng, double accuracy) {
    if (sample <= 0 || sample > now || now - sample > MAX_AGE || sample <= acceptedAt
        || !Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 85
        || Math.abs(lng) > 180 || !Double.isFinite(accuracy) || accuracy <= 0) return Decision.IGNORE;
    if (acceptedAt > 0 && now - acceptedAt <= PREFER_RECENT
        && accuracy > Math.max(25, precision * 2)) return Decision.IGNORE;
    if (acceptedAt > 0 && accuracy <= 100 && precision <= 100) {
      double distance = Rules.meters(latitude, longitude, lat, lng);
      double plausible = Math.max(150, (sample - acceptedAt) / 1000d * 55 + accuracy + precision);
      if (distance > plausible) {
        boolean confirmed = pendingAt > 0 && sample > pendingAt && sample - pendingAt <= MAX_AGE
            && Rules.meters(pendingLat, pendingLng, lat, lng) <= Math.max(40, accuracy * 2);
        if (!confirmed) {
          if (sample > pendingAt) { pendingAt = sample; pendingLat = lat; pendingLng = lng; }
          return Decision.SUSPECT;
        }
      }
    }
    acceptedAt = sample; latitude = lat; longitude = lng; precision = accuracy; pendingAt = 0;
    return Decision.ACCEPT;
  }

  void reset() { acceptedAt = 0; pendingAt = 0; }
}
