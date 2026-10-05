#!/usr/bin/bash

# update fcitx5-rime
echo "updating fcitx5-rime"
pushd plugin/rime/src/main/cpp/fcitx5-rime
git remote add gh https://github.com/fxliang/fcitx5-rime.git || git remote set-url gh https://github.com/fxliang/fcitx5-rime.git
git fetch -v gh master
git checkout gh/master
popd
sed -i 's|/fcitx/|/fxliang/|g' plugin/rime/licenses/libraries/fcitx5-rime.json

# apply fcitx5 patch from fcitx5-rime
echo "applying fcitx5 patch"
pushd lib/fcitx5/src/main/cpp/fcitx5
# reset to clean state first
git checkout -- .
git apply ../../../../../../plugin/rime/src/main/cpp/fcitx5-rime/fcitx5-alt-trigger-v4point1.patch || echo "fcitx5 patch already applied or failed"
popd

# update prebuilt
# fxliang's prebuilt snapshot predates nlohmann_json, which the upstream
# fcitx5-chinese-addons imported here needs; keep that path from the pinned commit
echo "updating prebuilt"
prebuilt_pin=$(git ls-tree HEAD -- lib/fcitx5/src/main/cpp/prebuilt | awk '{print $3}')
pushd lib/fcitx5/src/main/cpp/prebuilt
git remote add gh https://github.com/fxliang/prebuilt.git || git remote set-url gh https://github.com/fxliang/prebuilt.git
git fetch -v gh master
git checkout gh/master
git fetch -v origin "$prebuilt_pin" || true
git checkout "$prebuilt_pin" -- nlohmann_json || echo "WARNING: failed to restore prebuilt nlohmann_json"
popd
