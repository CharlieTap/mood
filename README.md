# Mood

![Mood](images/mood-banner.png)

Mood is a Kotlin Multiplatform project that turns the classic 1990s game Doom into
a playable application for multiplatform targets (Currently demonstrating Android and
Web).

The project was created for a couple of reasons:

- To explore a modern Kotlin Multiplatform project and get a handle on exactly how
much can be shared today
- As a stress test for [Chasm](https://github.com/CharlieTap/chasm) which the project
leverages to run the Doom game

Whilst I've made an effort to organise and tradcode parts of the codebase you'll
still find plenty of slop inside

## How does it work?

The original Doom game is compiled to WebAssembly. The
[WebAssembly binary](doom-runtime/src/commonMain/composeResources/files/doom/doom.wasm)
lives in `doom-runtime`, where Chasm's Gradle plugin reads it and generates type-safe
Kotlin bindings for its exported functions and memory. After code generation, those
sources can be found at
`doom-runtime/build/generated/kotlin/commonMain/com/tap/mood/doom/runtime/generated`.
On the web, the generated binding uses the browser's WebAssembly runtime; on Android,
it uses Chasm's interpreter.

[`doom-runtime`](doom-runtime/src/commonMain/kotlin/com/tap/mood/doom/runtime) contains
the shared code around that binding. It creates and drives a running
[game instance](doom-runtime/src/commonMain/kotlin/com/tap/mood/doom/runtime/instance/Instance.kt),
handles input and frames, and defines the interfaces used for audio, saved games,
logging and execution.

[`doom-ui`](doom-ui/src/commonMain/kotlin/com/tap/mood/doom/ui) contains the shared
Compose interface, including the game screen, settings and performance information.
[`game-controls`](game-controls/src/commonMain/kotlin/com/tap/mood/game/controls) contains
reusable virtual buttons and joysticks. Android enables these controls, while the web
application uses a keyboard-focused interface.

Rendering is split into three Kotlin Multiplatform modules:

- [`renderer`](renderer) contains the shared frontend contract, display settings,
  viewport calculation, input handling, renderer selection and ordered fallback.
- [`renderer:webgpu`](renderer/webgpu) contains one shared palette-indexed WebGPU
  pipeline and WGSL shader. In the browser it uses the WebGPU API
  and on Android it uses Jetpack WebGPU backed by Dawn/Vulkan.
- [`renderer:classic`](renderer/classic) contains the conventional platform renderers:
  Android `Bitmap`/`Canvas` and browser Canvas 2D.

Browser audio is streamed through an `AudioWorklet`; browsers without worklet support
fall back to scheduled `AudioBuffer` playback.

I initially wanted to have just one renderer backed by webgpu, but support is not quite
at 100% across web and android, so I've added a conventional fallback for each of the
platforms. There's a likely near future where Chasm supports the Component Model and we
can bake the webgpu calls into the wasm binary itself rather than adapting them.

## How to play?

You can try the web distribution hosted on Github pages:

https://charlietap.github.io/mood/

Alternatively you can build from source

### Android

```shell
./gradlew :android:assembleRelease
```

The release APK is written to `android/build/outputs/apk/release`. It must be signed
before installation when no release signing configuration is supplied locally.

### Web

```shell
./gradlew :web:wasmJsBrowserDistribution
```

The production distribution is written to `web/build/dist/wasmJs/productionExecutable`.

## Doom source

The Doom source used to produce the WebAssembly binary is available in [`CharlieTap/doom.wasm` at commit `ae336d991d3a297f011d99e333410108a47c6a6e`](https://github.com/CharlieTap/doom.wasm/tree/ae336d991d3a297f011d99e333410108a47c6a6e).

## License

Except for the Doom-derived WebAssembly module, Mood's source code is dual-licensed under both the MIT and Apache 2.0 licenses. You can choose which one you want to use the software under.

- For details on the MIT license, please see the [LICENSE-MIT](LICENSE-MIT) file.
- For details on the Apache 2.0 license, please see the [LICENSE-APACHE](LICENSE-APACHE) file.
