cmake_minimum_required(VERSION 3.22.1)
project(appmodules LANGUAGES CXX)
# Pinned RN setup registers core modules plus autolinked navigation/safe-area components.
include("${REACT_ANDROID_DIR}/cmake-utils/ReactNative-application.cmake")
target_link_options(appmodules PRIVATE "-Wl,-z,max-page-size=16384")
