# MYOS Liquid Lab

A standalone Android playground for a convincing 3D water volume with gravity-driven sloshing.

## Goals
- A fixed transparent container / viewport.
- A visibly three-dimensional liquid volume with depth, reflections, refraction-style shading and animated caustics.
- The free surface responds to device tilt and shake.
- No launcher, wallpaper, app icons, weather, or time-dependent artwork.
- Fish are a later optional layer. No floating props in the first prototype.

## Current prototype\nVersion 0.2.0 introduces a ray-marched 3D liquid volume inside a rounded vessel, with full three-axis device-gravity-oriented free-surface geometry, depth-dependent body shading, specular response, glass edge highlights and shake-driven surface disturbance. It remains an experimental real-time approximation rather than a full computational fluid dynamics solver.\n\n## Iteration
Appearance comes first. Each build should isolate a small set of visual and physics changes so screenshots and device tests can guide the next pass.

## Build
Open the project in Android Studio or use the included GitHub Actions workflow to build the debug APK.
