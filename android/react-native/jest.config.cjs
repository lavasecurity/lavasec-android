module.exports = {
  preset: '@react-native/jest-preset',
  setupFilesAfterEnv: ['<rootDir>/tests/phone-window.ts'],
  // The Android host runs Jest against the Android platform so `X.android.tsx`
  // wins over the platform-neutral fallback in `components.tsx`.
  haste: {defaultPlatform: 'android', platforms: ['android', 'ios', 'native']},
  // Bounded native navigation measurement, matching the shared iOS unit host.
  moduleNameMapper: {'^@react-navigation/elements$':'<rootDir>/tests/native-header-height-mock.ts'},
  // Exercise the installed navigation providers in the admission lifecycle regression.
  transformIgnorePatterns: ['/node_modules/(?!(react-native|@react-native|@react-navigation|react-native-screens|react-native-safe-area-context|use-latest-callback|nanoid)/)'],
  testMatch: ['<rootDir>/tests/**/*.test.[jt]s?(x)'],
};
