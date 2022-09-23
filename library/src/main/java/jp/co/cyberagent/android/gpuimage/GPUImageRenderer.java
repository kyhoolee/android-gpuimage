/*
 * Copyright (C) 2018 CyberAgent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.cyberagent.android.gpuimage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;
import java.util.Queue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.util.OpenGlUtils;
import jp.co.cyberagent.android.gpuimage.util.Rotation;
import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil;

import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_NO_ROTATION;

/**
 * Main filter logic of OpenGL-based filter - Renderer object
 */
public class GPUImageRenderer implements GLSurfaceView.Renderer, GLTextureView.Renderer, PreviewCallback {
    // 1. No image state
    private static final int NO_IMAGE = -1;
    // 2. Cube with 4 vertices - on 2D plane
    public static final float CUBE[] = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };

    // 3. OpenGL-based filter
    private GPUImageFilter filter;

    // 4. Lock for waiting surface change
    public final Object surfaceChangedWaiter = new Object();

    // 5. Address of image-content openGL-based texture - default is no image
    private int glTextureId = NO_IMAGE;

    // 6. SurfaceTexture
    private SurfaceTexture surfaceTexture = null;
    // 7. Cube buffer
    private final FloatBuffer glCubeBuffer;
    // 8. Texture buffer
    private final FloatBuffer glTextureBuffer;
    // 9. RGB buffer
    private IntBuffer glRgbBuffer;

    // 10. size of output, image, padding
    private int outputWidth;
    private int outputHeight;
    private int imageWidth;
    private int imageHeight;
    private int addedPadding;

    // 11. Queue of runnable task to run on draw
    private final Queue<Runnable> runOnDraw;

    // 12. Queue of runnable task to run on draw ended
    private final Queue<Runnable> runOnDrawEnd;

    // 13. Config: rotation, flip, scale
    private Rotation rotation;
    private boolean flipHorizontal;
    private boolean flipVertical;
    private GPUImage.ScaleType scaleType = GPUImage.ScaleType.CENTER_CROP;

    // 14. Background channel value
    private float backgroundRed = 0;
    private float backgroundGreen = 0;
    private float backgroundBlue = 0;

    public GPUImageRenderer(final GPUImageFilter filter) {
        this.filter = filter;

        // 1. Init runnable task queue: run-on-draw, run-on-draw-ended
        runOnDraw = new LinkedList<>();
        runOnDrawEnd = new LinkedList<>();

        // 2. Init cube buffer
        glCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        glCubeBuffer.put(CUBE).position(0);

        // 3. Init texture buffer
        glTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        // 4. Set rotation, flip
        setRotation(Rotation.NORMAL, false, false);
    }

    @Override
    public void onSurfaceCreated(final GL10 unused, final EGLConfig config) {
        GLES20.glClearColor(backgroundRed, backgroundGreen, backgroundBlue, 1);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        filter.ifNeedInit();
    }

    @Override
    public void onSurfaceChanged(final GL10 gl, final int width, final int height) {

        outputWidth = width;
        outputHeight = height;

        // 1. Set openGL viewport
        GLES20.glViewport(0, 0, width, height);

        // 2. Set openGL program address - get from filter program address
        GLES20.glUseProgram(filter.getProgram());

        // 3. Set output size for filter
        filter.onOutputSizeChanged(width, height);

        // 4. Adjust image scaling
        adjustImageScaling();

        // 5. Notify other tasks which waiting for surface changed
        synchronized (surfaceChangedWaiter) {
            surfaceChangedWaiter.notifyAll();
        }
    }

    @Override
    public void onDrawFrame(final GL10 gl) {
        // 1. Clear color buffer and depth buffer
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        // 2. Run all run-on-draw tasks
        runAll(runOnDraw);
        // 3. Call filter to run draw logic with: textureId, cubeBuffer, textureBuffer
        filter.onDraw(glTextureId, glCubeBuffer, glTextureBuffer);
        // 4. Run all run-on-draw-ended tasks
        runAll(runOnDrawEnd);
        if (surfaceTexture != null) {
            // Update the texture image to the most recent frame from the image stream
            surfaceTexture.updateTexImage();
        }
    }

    /**
     * Sets the background color
     *
     * @param red   red color value
     * @param green green color value
     * @param blue  red color value
     */
    public void setBackgroundColor(float red, float green, float blue) {
        backgroundRed = red;
        backgroundGreen = green;
        backgroundBlue = blue;
    }

    /**
     * Run all runnable tasks queue
     * @param queue
     */
    private void runAll(Queue<Runnable> queue) {
        synchronized (queue) {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera camera) {
        final Size previewSize = camera.getParameters().getPreviewSize();
        onPreviewFrame(data, previewSize.width, previewSize.height);
    }

    /**
     * Called as preview frames are displayed.
     * This callback is invoked on the event thread open(int) was called from.
     * @param data
     * @param width
     * @param height
     */
    public void onPreviewFrame(final byte[] data, final int width, final int height) {
        if (glRgbBuffer == null) {
            glRgbBuffer = IntBuffer.allocate(width * height);
        }
        if (runOnDraw.isEmpty()) {
            runOnDraw(new Runnable() {
                @Override
                public void run() {
                    // 1. Convert YUV data from camera to RBGA format
                    GPUImageNativeLibrary.YUVtoRBGA(data, width, height, glRgbBuffer.array());
                    // 2. Load RGBA data for openGL texture
                    glTextureId = OpenGlUtils.loadTexture(glRgbBuffer, width, height, glTextureId);

                    if (imageWidth != width) {
                        imageWidth = width;
                        imageHeight = height;
                        adjustImageScaling();
                    }
                }
            });
        }
    }

    /**
     * Set this surfaceTexture to camera
     * @param camera
     */
    public void setUpSurfaceTexture(final Camera camera) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                surfaceTexture = new SurfaceTexture(textures[0]);
                try {
                    // 1. Sets the SurfaceTexture to be used for live preview.
                    // Either a surface or surface texture is necessary for preview, and preview is necessary to take picture
                    camera.setPreviewTexture(surfaceTexture);

                    // 2. Installs a callback to be invoked for every preview frame in addition to displaying them on the screen
                    camera.setPreviewCallback(GPUImageRenderer.this);

                    // 3. Starts capturing and drawing preview frames to the screen
                    camera.startPreview();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Set new filter to this renderer
     * @param filter
     */
    public void setFilter(final GPUImageFilter filter) {
        runOnDraw(new Runnable() {

            @Override
            public void run() {

                // 1. Get current filter as old filter
                final GPUImageFilter oldFilter = GPUImageRenderer.this.filter;
                // 2. Set new filter to this filter
                GPUImageRenderer.this.filter = filter;
                // 3. Destroy old filter
                if (oldFilter != null) {
                    oldFilter.destroy();
                }
                // 4. Init new filter if needed
                GPUImageRenderer.this.filter.ifNeedInit();
                // 5. Set OpenGL program address to new filter program address
                GLES20.glUseProgram(GPUImageRenderer.this.filter.getProgram());
                // 6. Set output size for new filter
                GPUImageRenderer.this.filter.onOutputSizeChanged(outputWidth, outputHeight);
            }
        });
    }

    /**
     * Delete current image
     */
    public void deleteImage() {
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                // 1. Delete OpenGL texture linked with current image
                GLES20.glDeleteTextures(1, new int[]{
                        glTextureId
                }, 0);
                // 2. Set texture state to no image
                glTextureId = NO_IMAGE;
            }
        });
    }

    /**
     *
     * @param bitmap
     */
    public void setImageBitmap(final Bitmap bitmap) {
        setImageBitmap(bitmap, true);
    }

    /**
     *
     * @param bitmap
     * @param recycle
     */
    public void setImageBitmap(final Bitmap bitmap, final boolean recycle) {
        if (bitmap == null) {
            return;
        }

        runOnDraw(new Runnable() {

            @Override
            public void run() {

                // 1. Resize image - add padding 1 if bitmap width % 2 == 1
                Bitmap resizedBitmap = null;
                if (bitmap.getWidth() % 2 == 1) {
                    resizedBitmap = Bitmap.createBitmap(bitmap.getWidth() + 1, bitmap.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    resizedBitmap.setDensity(bitmap.getDensity());
                    Canvas can = new Canvas(resizedBitmap);
                    can.drawARGB(0x00, 0x00, 0x00, 0x00);
                    can.drawBitmap(bitmap, 0, 0, null);
                    addedPadding = 1;
                } else {
                    addedPadding = 0;
                }

                // 2. Put image bitmap to openGL texture address
                glTextureId = OpenGlUtils.loadTexture(
                        resizedBitmap != null ? resizedBitmap : bitmap, glTextureId, recycle);

                // 3. Recycle bitmap if needed
                if (resizedBitmap != null) {
                    resizedBitmap.recycle();
                }

                // 4. Adjust new bitmap scaling
                imageWidth = bitmap.getWidth();
                imageHeight = bitmap.getHeight();
                adjustImageScaling();
            }
        });
    }

    /**
     *
     * @param scaleType
     */
    public void setScaleType(GPUImage.ScaleType scaleType) {
        this.scaleType = scaleType;
    }

    /**
     *
     * @return
     */
    protected int getFrameWidth() {
        return outputWidth;
    }

    /**
     *
     * @return
     */
    protected int getFrameHeight() {
        return outputHeight;
    }

    /**
     *
     */
    private void adjustImageScaling() {
        // 1. Current output width, height
        float outputWidth = this.outputWidth;
        float outputHeight = this.outputHeight;

        // 2. Switch width height if rotation
        if (rotation == Rotation.ROTATION_270 || rotation == Rotation.ROTATION_90) {
            outputWidth = this.outputHeight;
            outputHeight = this.outputWidth;
        }

        // 3. Ratio between output and image
        float ratio1 = outputWidth / imageWidth;
        float ratio2 = outputHeight / imageHeight;
        float ratioMax = Math.max(ratio1, ratio2);

        int imageWidthNew = Math.round(imageWidth * ratioMax);
        int imageHeightNew = Math.round(imageHeight * ratioMax);

        float ratioWidth = imageWidthNew / outputWidth;
        float ratioHeight = imageHeightNew / outputHeight;

        // 4. Rotate texture with rotation, flip horizontal, flip vertical
        float[] cube = CUBE;
        float[] textureCords = TextureRotationUtil.getRotation(rotation, flipHorizontal, flipVertical);


        // 5. Update new textureCords or cube size based scale type
        if (scaleType == GPUImage.ScaleType.CENTER_CROP) {
            float distHorizontal = (1 - 1 / ratioWidth) / 2;
            float distVertical = (1 - 1 / ratioHeight) / 2;
            textureCords = new float[]{
                    addDistance(textureCords[0], distHorizontal), addDistance(textureCords[1], distVertical),
                    addDistance(textureCords[2], distHorizontal), addDistance(textureCords[3], distVertical),
                    addDistance(textureCords[4], distHorizontal), addDistance(textureCords[5], distVertical),
                    addDistance(textureCords[6], distHorizontal), addDistance(textureCords[7], distVertical),
            };
        } else {
            cube = new float[]{
                    CUBE[0] / ratioHeight, CUBE[1] / ratioWidth,
                    CUBE[2] / ratioHeight, CUBE[3] / ratioWidth,
                    CUBE[4] / ratioHeight, CUBE[5] / ratioWidth,
                    CUBE[6] / ratioHeight, CUBE[7] / ratioWidth,
            };
        }

        // 6. Update cube buffer with new cube scale
        glCubeBuffer.clear();
        glCubeBuffer.put(cube).position(0);

        // 7. Update texture buffer with new texture coord
        glTextureBuffer.clear();
        glTextureBuffer.put(textureCords).position(0);
    }

    /**
     *
     * @param coordinate
     * @param distance
     * @return
     */
    private float addDistance(float coordinate, float distance) {
        return coordinate == 0.0f ? distance : 1 - distance;
    }

    /**
     *
     * @param rotation
     * @param flipHorizontal
     * @param flipVertical
     */
    public void setRotationCamera(final Rotation rotation, final boolean flipHorizontal,
                                  final boolean flipVertical) {
        setRotation(rotation, flipVertical, flipHorizontal);
    }

    /**
     *
     * @param rotation
     */
    public void setRotation(final Rotation rotation) {
        this.rotation = rotation;
        adjustImageScaling();
    }

    /**
     *
     * @param rotation
     * @param flipHorizontal
     * @param flipVertical
     */
    public void setRotation(final Rotation rotation,
                            final boolean flipHorizontal, final boolean flipVertical) {
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        setRotation(rotation);
    }

    public Rotation getRotation() {
        return rotation;
    }

    public boolean isFlippedHorizontally() {
        return flipHorizontal;
    }

    public boolean isFlippedVertically() {
        return flipVertical;
    }

    /**
     * Add runnable-task to run-on-draw task queue
     * @param runnable
     */
    protected void runOnDraw(final Runnable runnable) {
        synchronized (runOnDraw) {
            runOnDraw.add(runnable);
        }
    }

    /**
     * Add runnable-task to run-on-draw-ended task queue
     * @param runnable
     */
    protected void runOnDrawEnd(final Runnable runnable) {
        synchronized (runOnDrawEnd) {
            runOnDrawEnd.add(runnable);
        }
    }
}
