# MYOS Liquid Lab

A standalone Android playground for a convincing 3D water volume with gravity-driven sloshing.

## Goals
- A fixed transparent container / viewport.
- A visibly three-dimensional liquid volume with depth, reflections, refraction-style shading and animated caustics.
- The free surface responds to device tilt and shake with inertia, momentum and gradual settling.
- No launcher, wallpaper, app icons, weather, or time-dependent artwork.
- Fish are a later optional layer. No floating props in the first prototype.

## Current prototype
Version 0.2.2 adds a damped spring response for the liquid: the water lags behind device orientation, carries momentum, overshoots and settles. Motion impulses feed into the liquid velocity, and the surface develops stronger moving ripples while the liquid is in motion. This builds on the ray-marched 3D volume introduced in 0.2.0 and the sensor refinements in 0.2.1.

It remains an experimental real-time rendering approximation rather than a full computational fluid dynamics solver. Android CI validates the app build, but GLSL shader compilation and visual quality still need verification on a physical device.

## Iteration
Appearance and believable motion come first. Each build should isolate a small set of visual and physics changes so screenshots and device tests can guide the next pass.

## Build
Open the project in Android Studio or use the included GitHub Actions workflow to build the debug APK.
