# MYOS Liquid Lab

A standalone Android playground for a convincing 3D water volume with gravity-driven sloshing.

## Goals
- A fixed transparent container / viewport.
- A visibly three-dimensional liquid volume with depth, reflections, refraction-style shading and animated caustics.
- The free surface responds to device tilt and shake with inertia, momentum and gradual settling.
- No launcher, wallpaper, app icons, weather, or time-dependent artwork.
- Fish are a later optional layer. No floating props in the first prototype.

## Current prototype — 0.3.0
Version 0.3.0 introduces a separate `FluidSurfaceSimulation` physics layer. It evolves a low-resolution 2D shallow-water field (surface height plus horizontal velocity) with continuity, pressure-gradient response, damping and reflecting vessel boundaries. Accelerometer motion injects momentum into the field.

The ray-marched 3D renderer remains the visual baseline. It samples the simulated height field as a texture to deform the liquid free surface. The existing inertial tilt response remains in place, so the surface combines the large-scale gravity orientation with locally simulated sloshing.

This is an intentionally lightweight first step toward fluid dynamics, not a full 3D Navier–Stokes, PBF or MLS-MPM solver. It does not yet simulate a fully deformable 3D volume or physically correct glass refraction. The 40×40 CPU grid is a prototype tradeoff; performance and visual response must be checked on a physical Android device.

## Research references
- [Lagrangian Fluid Simulation for Android](https://github.com/MartinOpat/Lagrangian-fluid-simulation-for-Android) — native Android/C++ particle simulation architecture.
- [Inkling](https://github.com/mishurov/fluid) — mobile OpenGL shader-based Navier–Stokes solver concepts.
- [Real-time Position-Based Fluids](https://github.com/xuxmin/pbf) — particle fluid simulation and screen-space fluid rendering.
- [Fluid Cube](https://github.com/jta5456/fluid-cube) — 3D liquid simulation and transparent-container rendering reference.

These projects are research references; this repository contains an independent implementation and does not copy their source code.

## Iteration
Appearance and believable motion come first. Each build should isolate a small set of visual and physics changes so screenshots and device tests can guide the next pass.

## Build
Open the project in Android Studio or use the included GitHub Actions workflow to build the debug APK.
