# GeoMathTest.kt

**Path:** `tracking-core/src/commonTest/kotlin/tracker/GeoMathTest.kt` · **Layer:** tests (geometry)

## Purpose
Sanity checks of `GeoMath.haversineMeters`.

## Tests
| Test | What it checks |
|---|---|
| `oneDegreeOfLatitudeIsAbout111Km` | One degree of latitude is 111 195 m within 50 m. |
| `samePointIsZero` | The distance from a point to itself is exactly 0. |

## Run
`cd tracking-core && ./gradlew jvmTest`
