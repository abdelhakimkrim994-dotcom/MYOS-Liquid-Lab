package com.myos.liquidlab

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Small, deterministic shallow-water surface solver.
 *
 * This is deliberately separate from rendering: it evolves surface height and
 * horizontal velocity on a modest grid, then exposes the height field as a
 * texture for the renderer. It is a mobile-friendly first physics layer, not
 * a full 3D Navier-Stokes/MPM solver.
 */
internal class FluidSurfaceSimulation(private val size: Int = 40) {
    private val count = size * size
    private val height = FloatArray(count)
    private val nextHeight = FloatArray(count)
    private val velocityX = FloatArray(count)
    private val velocityZ = FloatArray(count)
    private val nextVelocityX = FloatArray(count)
    private val nextVelocityZ = FloatArray(count)
    private val uploadBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val spacing = 2f / (size - 1)
    private val waterDepth = 0.30f
    private val gravity = 1.35f
    private val damping = 1.15f
    private var impulseX = 0f
    private var impulseZ = 0f

    @Synchronized
    fun addImpulse(x: Float, z: Float) {
        impulseX = (impulseX + x).coerceIn(-3.5f, 3.5f)
        impulseZ = (impulseZ + z).coerceIn(-3.5f, 3.5f)
    }

    fun step(deltaSeconds: Float) {
        val dt = deltaSeconds.coerceIn(0f, 1f / 30f)
        if (dt <= 0f) return

        val ix: Float
        val iz: Float
        synchronized(this) {
            ix = impulseX
            iz = impulseZ
            impulseX = 0f
            impulseZ = 0f
        }

        // Apply phone-motion impulses as momentum, strongest in the interior.
        if (ix != 0f || iz != 0f) {
            for (z in 1 until size - 1) {
                for (x in 1 until size - 1) {
                    val nx = (x - (size - 1) * 0.5f) / (size * 0.5f)
                    val nz = (z - (size - 1) * 0.5f) / (size * 0.5f)
                    val centerWeight = 0.72f + 0.28f * (1f - min(1f, nx * nx + nz * nz))
                    val i = z * size + x
                    velocityX[i] += ix * centerWeight
                    velocityZ[i] += iz * centerWeight
                }
            }
        }

        val invTwoDx = 0.5f / spacing
        val invDx2 = 1f / (spacing * spacing)
        val dampingFactor = max(0f, 1f - damping * dt)

        // Shallow-water continuity and momentum equations, with solid side walls.
        for (z in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val i = z * size + x
                val left = i - 1
                val right = i + 1
                val down = i - size
                val up = i + size

                val divergence =
                    (velocityX[right] - velocityX[left] + velocityZ[up] - velocityZ[down]) * invTwoDx
                nextHeight[i] = (height[i] - waterDepth * divergence * dt).coerceIn(-0.11f, 0.11f)

                val gradientX = (height[right] - height[left]) * invTwoDx
                val gradientZ = (height[up] - height[down]) * invTwoDx
                val laplacian = (height[left] + height[right] + height[down] + height[up] - 4f * height[i]) * invDx2

                // Pressure-gradient response, mild viscosity and momentum damping.
                nextVelocityX[i] = (velocityX[i] - gravity * gradientX * dt) * dampingFactor
                nextVelocityZ[i] = (velocityZ[i] - gravity * gradientZ * dt) * dampingFactor
                // A small curvature term suppresses grid noise without erasing wave motion.
                nextVelocityX[i] += 0.0008f * laplacian * dt
                nextVelocityZ[i] += 0.0008f * laplacian * dt
            }
        }

        // Reflecting walls: no fluid crosses the vessel boundary.
        for (x in 0 until size) {
            val top = (size - 1) * size + x
            val bottom = x
            nextHeight[top] = nextHeight[top - size]
            nextHeight[bottom] = nextHeight[bottom + size]
            nextVelocityX[top] = nextVelocityX[top - size]
            nextVelocityX[bottom] = nextVelocityX[bottom + size]
            nextVelocityZ[top] = 0f
            nextVelocityZ[bottom] = 0f
        }
        for (z in 0 until size) {
            val left = z * size
            val right = left + size - 1
            nextHeight[left] = nextHeight[left + 1]
            nextHeight[right] = nextHeight[right - 1]
            nextVelocityX[left] = 0f
            nextVelocityX[right] = 0f
            nextVelocityZ[left] = nextVelocityZ[left + 1]
            nextVelocityZ[right] = nextVelocityZ[right - 1]
        }

        System.arraycopy(nextHeight, 0, height, 0, count)
        System.arraycopy(nextVelocityX, 0, velocityX, 0, count)
        System.arraycopy(nextVelocityZ, 0, velocityZ, 0, count)
    }

    @Synchronized
    fun writeHeightTexture(buffer: FloatBuffer) {
        buffer.clear()
        buffer.put(height)
        buffer.position(0)
    }

    fun createUploadBuffer(): FloatBuffer = uploadBuffer
    fun resolution(): Int = size
}
