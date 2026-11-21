# Changelog

## [2.0.0](https://github.com/ketronkowski/xlights-wled-integration/compare/v1.0.0...v2.0.0) (2026-11-21)


### ⚠ BREAKING CHANGES

* daily read-only pre-show check with push and email report ([#34](https://github.com/ketronkowski/xlights-wled-integration/issues/34))

### Features

* daily read-only pre-show check with push and email report ([#34](https://github.com/ketronkowski/xlights-wled-integration/issues/34)) ([dedd199](https://github.com/ketronkowski/xlights-wled-integration/commit/dedd19965d4c659fa08c0bdb03ebd11460367380))
* give floodlights their own look in Set Look ([#30](https://github.com/ketronkowski/xlights-wled-integration/issues/30)) ([6698fa4](https://github.com/ketronkowski/xlights-wled-integration/commit/6698fa4168499fa3699aa3b1eaed46cee860b78e))
* merge back-to-back null models into one WLED segment ([#37](https://github.com/ketronkowski/xlights-wled-integration/issues/37)) ([a9508ad](https://github.com/ketronkowski/xlights-wled-integration/commit/a9508ad20490c75dada2fa5e09197bc7fb0d92e5))
* merge xLights model groups into single WLED segments ([#31](https://github.com/ketronkowski/xlights-wled-integration/issues/31)) ([a5bf163](https://github.com/ketronkowski/xlights-wled-integration/commit/a5bf163cd7fd42a33644beafe6aca18c2f2a353d))
* save new segments into each board's boot preset so they survive a reboot ([#36](https://github.com/ketronkowski/xlights-wled-integration/issues/36)) ([c1aeef6](https://github.com/ketronkowski/xlights-wled-integration/commit/c1aeef64ceaf17af6f5b93cfed6b1b8d0d2a184b))
* Set Look action for selected WLED controllers ([#28](https://github.com/ketronkowski/xlights-wled-integration/issues/28)) ([0f1a239](https://github.com/ketronkowski/xlights-wled-integration/commit/0f1a239f9201cd2ce6eca5f18cefcfe243422063))


### Bug Fixes

* missing FPP schedule only warns before the first show night ([#35](https://github.com/ketronkowski/xlights-wled-integration/issues/35)) ([7b3ea79](https://github.com/ketronkowski/xlights-wled-integration/commit/7b3ea79749233a2a9d53f9d5f9e05eca38e46568))
* pair WLED controllers by their configured IP, not only by name ([#32](https://github.com/ketronkowski/xlights-wled-integration/issues/32)) ([aafecc9](https://github.com/ketronkowski/xlights-wled-integration/commit/aafecc9a10fb78997deca637ff21e598f99edc5d))
* pre-show check warns only when a board is lit, not just off its boot preset ([#38](https://github.com/ketronkowski/xlights-wled-integration/issues/38)) ([da692b3](https://github.com/ketronkowski/xlights-wled-integration/commit/da692b3d9611e124070166dc73c49dbc74949ecc))

## [1.0.0](https://github.com/ketronkowski/xlights-wled-integration/compare/v0.2.0...v1.0.0) (2026-11-13)


### Features

* add progress modal for bulk controller operations ([#9](https://github.com/ketronkowski/xlights-wled-integration/issues/9)) ([26e74be](https://github.com/ketronkowski/xlights-wled-integration/commit/26e74be7c5d4331a54de34943388e4aa00e7e047))

## [0.2.0](https://github.com/ketronkowski/xlights-wled-integration/compare/v0.1.0...v0.2.0) (2026-11-13)


### Features

* add Clear All Controllers destructive reset ([#8](https://github.com/ketronkowski/xlights-wled-integration/issues/8)) ([1ca5ae0](https://github.com/ketronkowski/xlights-wled-integration/commit/1ca5ae08b6c97a02e0460dbd574573d224a104f1))
* add phone boundary finder for measuring null pixels ([#15](https://github.com/ketronkowski/xlights-wled-integration/issues/15)) ([912ccae](https://github.com/ketronkowski/xlights-wled-integration/commit/912ccaea770692f8236ea93bd298bbdd068b76a6))
* add PR preview environments ([#11](https://github.com/ketronkowski/xlights-wled-integration/issues/11)) ([dc843b3](https://github.com/ketronkowski/xlights-wled-integration/commit/dc843b3b06d225b8d8eb35a4330f3281f885c899))
* add Technitium DNS sync script for Light Show controllers ([#16](https://github.com/ketronkowski/xlights-wled-integration/issues/16)) ([1e9904d](https://github.com/ketronkowski/xlights-wled-integration/commit/1e9904d39153f21e9ce664d7621d7c87ae204e6d))
* link controller addresses to their WLED UI ([#20](https://github.com/ketronkowski/xlights-wled-integration/issues/20)) ([529109b](https://github.com/ketronkowski/xlights-wled-integration/commit/529109b84dd40f9a8e9c261c625dc47119913332))
* treat FPP as a show controller with an access check ([#21](https://github.com/ketronkowski/xlights-wled-integration/issues/21)) ([c89024c](https://github.com/ketronkowski/xlights-wled-integration/commit/c89024c648d28a45f9ac6cbc6b481186ff8bbe67))


### Bug Fixes

* make Update Selected converge on WLED segments ([#18](https://github.com/ketronkowski/xlights-wled-integration/issues/18)) ([e046221](https://github.com/ketronkowski/xlights-wled-integration/commit/e0462217764ecb54e0bf2b999581def561e51aac))
* read custom model node count from the node field ([#19](https://github.com/ketronkowski/xlights-wled-integration/issues/19)) ([a40a0c1](https://github.com/ketronkowski/xlights-wled-integration/commit/a40a0c197ed8d5f61b983ac649cc611a5ec529ad))
* reload picks up new UI builds, and FPP no longer drops the connection ([#22](https://github.com/ketronkowski/xlights-wled-integration/issues/22)) ([1a6dcac](https://github.com/ketronkowski/xlights-wled-integration/commit/1a6dcac7f8e6c5137514500c535bb52cc26b009e))
* restore folder label from persisted upload status on page load ([#10](https://github.com/ketronkowski/xlights-wled-integration/issues/10)) ([bab4233](https://github.com/ketronkowski/xlights-wled-integration/commit/bab42337a7a9df0ee15d82529db3df3cde434424))
* **show-dns:** rename front-floods to front-far-left ([#17](https://github.com/ketronkowski/xlights-wled-integration/issues/17)) ([9f7ae93](https://github.com/ketronkowski/xlights-wled-integration/commit/9f7ae93cd56c1c0eccdc0af3f90aeb416963a4a5))
* tag preview images per-commit so pushes actually redeploy ([#14](https://github.com/ketronkowski/xlights-wled-integration/issues/14)) ([a9f4760](https://github.com/ketronkowski/xlights-wled-integration/commit/a9f476089273ea2d6a4f104c9ab4a8d7fdc89899))
* use relative API paths so the app works under a URL path prefix ([#13](https://github.com/ketronkowski/xlights-wled-integration/issues/13)) ([beaafab](https://github.com/ketronkowski/xlights-wled-integration/commit/beaafabd8dde578a6b7c63df5e378e9ab3a6a378))
