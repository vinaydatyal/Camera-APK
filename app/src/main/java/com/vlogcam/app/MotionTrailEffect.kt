package com.vlogcam.app

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Motion blur for fast-forwarded parts: inside [rangesUs] (input time) each output frame is a
 * running blend of the recent input frames, so when the sped-up video later keeps only every
 * Nth frame, the skipped moments show up as smooth motion streaks instead of jumps.
 * Outside the ranges frames pass through unchanged.
 */
@OptIn(UnstableApi::class)
class MotionTrailEffect(
    private val rangesUs: List<Pair<Long, Long>>,
    private val frames: Int,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(rangesUs, frames.coerceIn(2, 16), useHdr)

    private class Program(
        private val rangesUs: List<Pair<Long, Long>>,
        private val frames: Int,
        private val useHdr: Boolean,
    ) : BaseGlShaderProgram(useHdr, 1) {

        private val glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
            setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        }
        private var accTex = -1
        private var accFbo = -1
        private var width = 0
        private var height = 0
        private var hasAccumulation = false

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth != width || inputHeight != height) {
                deleteAccumulation()
                width = inputWidth
                height = inputHeight
                accTex = GlUtil.createTexture(width, height, useHdr)
                accFbo = GlUtil.createFboForTexture(accTex)
                hasAccumulation = false
            }
            return Size(width, height)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // The output buffer the base class prepared for this frame.
                val bound = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
                val outputFbo = bound[0]

                val inFast = rangesUs.any { presentationTimeUs >= it.first && presentationTimeUs < it.second }
                val weight = if (inFast && hasAccumulation) 1f / frames else 1f

                // 1) accumulate: acc = acc * (1 - w) + input * w
                GlUtil.focusFramebufferUsingCurrentContext(accFbo, width, height)
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.bindAttributesAndUniforms()
                if (weight < 1f) {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendColor(0f, 0f, 0f, weight)
                    GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA)
                }
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisable(GLES20.GL_BLEND)
                hasAccumulation = true

                // 2) copy the accumulated frame to the output
                GlUtil.focusFramebufferUsingCurrentContext(outputFbo, width, height)
                glProgram.setSamplerTexIdUniform("uTexSampler", accTex, 0)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun flush() {
            hasAccumulation = false
            super.flush()
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
                deleteAccumulation()
            } catch (e: GlUtil.GlException) {
                Log.w("MotionTrail", "Release failed", e)
            }
        }

        private fun deleteAccumulation() {
            if (accFbo != -1) GlUtil.deleteFbo(accFbo)
            if (accTex != -1) GlUtil.deleteTexture(accTex)
            accFbo = -1
            accTex = -1
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
        """
    }
}
