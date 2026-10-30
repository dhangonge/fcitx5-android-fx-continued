# The module ships prebuilt .so files only (see build.gradle.kts), but
# native-app-convention points every native module at this file, and AGP fails when
# the path is missing. An empty project is the smallest valid target.
cmake_minimum_required(VERSION 3.22)
project(voice-engine LANGUAGES C)
