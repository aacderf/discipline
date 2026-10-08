# Third-party notices

The root MIT license applies to the original project code and original assets. It does not relicense third-party software or Android system fonts.

| Component | License / terms | Notice |
| --- | --- | --- |
| Lucide exported icons | ISC; included Feather-derived icons retain their notices | `app/src/main/assets/licenses/LUCIDE-LICENSE.txt` |
| Morphicons icon interpolation | MIT | `app/src/main/assets/licenses/MORPHICONS-LICENSE.txt` |
| Leaflet vendored JS/CSS (legacy map assets) | BSD-2-Clause | `app/src/main/assets/map/LICENSE-Leaflet.txt` |
| Gradle wrapper | Apache-2.0 | Header notices in `gradlew` and `gradlew.bat`; [Gradle license](https://github.com/gradle/gradle/blob/master/LICENSE) |
| AndroidX, Kotlin, Haze and other Maven dependencies | Their respective upstream licenses | Refer to the pinned dependencies in `app/build.gradle` and upstream publications |
| AMap Android SDK | AMap SDK and service terms; not covered by this project's MIT license | [AMap documentation](https://lbs.amap.com/api/android-sdk/summary), [privacy policy](https://lbs.amap.com/pages/privacy/) |

No third-party SDK binary is checked into this repository. Maven dependencies are resolved during builds. System fonts are supplied by Android rather than bundled here.