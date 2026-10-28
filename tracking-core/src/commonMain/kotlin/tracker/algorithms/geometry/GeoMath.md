# GeoMath.kt

**Path:** `.../tracker/algorithms/geometry/GeoMath.kt` · **Layer:** algorithm (geometry)

## Purpose
Geodesy helpers on a sphere: great-circle distance (haversine), conversion to and from a local tangent plane (metres east/north), and projection of a point onto a segment. Accurate to well under a metre per kilometre.

## Types
| Type | Description |
|---|---|
| `XY(x, y)` | A point on the tangent plane: metres east (`x`) and north (`y`) of an origin. |
| `Projection(fraction, distanceMeters, latitude, longitude)` | Foot of the perpendicular: position along the segment (0 = start, 1 = end), lateral distance to the segment, coordinates of the foot. |

## `object GeoMath`
| Member | Description |
|---|---|
| `EARTH_RADIUS_METERS` | 6 371 008.8 m. |
| `valid(lat, lon)` | True for a finite coordinate on Earth (latitude -90..90, longitude -180..180). |
| `haversineMeters(lat1, lon1, lat2, lon2)` | Distance in metres. Throws `IllegalArgumentException` for an invalid coordinate; longitudes wrap at ±180°. |
| `metersPerDegreeLat()` | Metres per degree of latitude. |
| `metersPerDegreeLon(latitude)` | Metres per degree of longitude at that latitude. |
| `toXY(originLat, originLon, lat, lon)` | Coordinates → `XY` relative to the origin. **Small areas only** (a few kilometres). The longitude difference wraps at ±180°; refuses the poles and invalid input. |
| `fromXY(originLat, originLon, p)` | `XY` → (latitude, longitude) pair, wrapped into [-180, 180); refuses the poles and a latitude outside the Earth. |
| `project(lat, lon, aLat, aLon, bLat, bLon)` | Projects the point onto the segment a–b, clamped to its ends. |

## Notes
No `java.*` API, so it runs on iOS (Kotlin/Native). A zero-length segment yields `fraction = 0`.

## Used by
`GpsDistanceEngine`, `Kalman2D`, `StopDetector`, `DouglasPeucker`, `RoadGraph`, `Dbscan`, `KMeans`, `HmmMapMatcher`.
