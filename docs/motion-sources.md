# Motion references for 0.38

The interface uses original Compose implementation inspired by these GitHub examples, with no vendored navigation library or extra motion dependency:

- Canopas Compose Animated Navigation Bar: https://github.com/canopas/compose-animated-navigationbar — spring indicator and navigation transition ideas; Apache-2.0.
- Simform SSComposeCookBook: https://github.com/SimformSolutionsPvtLtd/SSComposeCookBook — Compose content transitions and interaction animation examples; MIT.

Emotion Ball assets ball.js, rings.js, emotions.js and engine.js were compared byte-for-byte with the supplied OpenNomi 0.29 APK before editing. The shape and emotion definitions remain unchanged. In 0.38, engine.js only targets approximately 30fps; nomi.html adds transparent background and explicit motion/lifecycle control. Existing LICENSE and NOTICE remain bundled.

AIRI keeps its original in-app website and native hearing integration. It still uses the user's configured AIRI services; no offline model bridge remains.
