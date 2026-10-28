#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_tracking_gps.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_tracking_gps'
  s.version          = '0.0.1-beta'
  s.summary          = 'Own GPS tracking engine for Flutter.'
  s.description      = <<-DESC
Own GPS tracking engine for Flutter.
                       DESC
  s.homepage         = 'https://code.vng.vn/newuprace/mobileapp/uprace-package'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Nguyễn Công Tuấn' => 'nguyencongtuan.devmobile@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*.{swift,h,m}'
  s.dependency 'Flutter'
  s.platform = :ios, '14.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.9'
  s.frameworks = 'CoreLocation', 'CoreMotion'
  # Shared Kotlin core, built by tool/build_core.sh (not committed).
  s.vendored_frameworks = 'Frameworks/TrackingCore.xcframework'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  s.resource_bundles = {'flutter_tracking_gps_privacy' => ['Resources/PrivacyInfo.xcprivacy']}
end
