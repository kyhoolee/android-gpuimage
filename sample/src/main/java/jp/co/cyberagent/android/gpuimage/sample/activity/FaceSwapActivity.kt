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

package jp.co.cyberagent.android.gpuimage.sample.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.solutions.facemesh.FaceMesh
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions
import com.google.mediapipe.solutions.facemesh.FaceMeshResult
import jp.co.cyberagent.android.gpuimage.FaceSwapFilter
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.GPUImageView
import jp.co.cyberagent.android.gpuimage.SwapFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSobelEdgeDetectionFilter
import jp.co.cyberagent.android.gpuimage.sample.R
import jp.co.cyberagent.android.gpuimage.sample.utils.BitmapUtils
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class FaceSwapActivity : AppCompatActivity() {

    private var facemesh: FaceMesh? = null
    // Executor
    private var executor: Executor? = null


    private var maskImage: Bitmap? = null
    private var maskFace: FaceMeshResult? = null


    private var originImage: Bitmap? = null
    private var originFace: FaceMeshResult? = null

    private var imageUri:Uri? = null

    private lateinit var gpuImage: GPUImage

    //    private var filterAdjuster: FilterAdjuster? = null
//    private val gpuImageView: GPUImageView by lazy { findViewById<GPUImageView>(R.id.gpuimage) }
    private val imageView: ImageView by lazy { findViewById<ImageView>(R.id.gpuimage) }
//    private val seekBar: SeekBar by lazy { findViewById<SeekBar>(R.id.seekBar) }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        maskImage = BitmapFactory.decodeResource(this.resources, R.drawable.mask)

        gpuImage = GPUImage(this)
        executor = Executors.newSingleThreadExecutor()

        facemesh = FaceMesh(
            this,
            FaceMeshOptions.builder()
                .setStaticImageMode(true)
                .setRefineLandmarks(true)
                .setMaxNumFaces(1)
                .setRunOnGpu(false)
                .build()
        )



//        seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                filterAdjuster?.adjust(progress)
//                gpuImageView.requestRender()
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })

//        findViewById<View>(R.id.button_choose_filter).setOnClickListener {
//            GPUImageFilterTools.showDialog(this) { filter ->
//                switchFilterTo(filter)
//                gpuImageView.requestRender()
//            }
//        }


        startPhotoPicker()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_PICK_IMAGE -> if (resultCode == RESULT_OK) {
                //gpuImageView.setImage(data!!.data)
                this.imageUri = data!!.data
                this.originImage = BitmapUtils.handleSamplingAndRotationBitmap(
                    this,
                    imageUri
                )

                //gpuImageView.setImage(imageUri)


//                gpuImage.setFilter(GPUImageSobelEdgeDetectionFilter())
//                val img: Bitmap = gpuImage.getBitmapWithFilterApplied(this.maskImage)
//                imageView.setImageBitmap(img)


                detectOriginFace()
            } else {
                finish()
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun startPhotoPicker() {
        val photoPickerIntent = Intent(Intent.ACTION_PICK)
        photoPickerIntent.type = "image/*"
        startActivityForResult(photoPickerIntent, REQUEST_PICK_IMAGE)
    }



//    private fun switchFilterTo(filter: GPUImageFilter) {
//        if (gpuImageView.filter == null || gpuImageView.filter.javaClass != filter.javaClass) {
//            gpuImageView.filter = filter
//            filterAdjuster = FilterAdjuster(filter)
//            if (filterAdjuster!!.canAdjust()) {
//                seekBar.visibility = View.VISIBLE
//                filterAdjuster!!.adjust(seekBar.progress)
//            } else {
//                seekBar.visibility = View.GONE
//            }
//        }
//    }

    private fun detectMaskFace() {

        executor!!.execute {
            facemesh!!.setResultListener { result: FaceMeshResult? ->
                maskFace = result
                Log.e("GPUSwap", "Detect mask-face")

                makeFaceMask()
            }

            facemesh!!.setErrorListener { message: String, _: java.lang.RuntimeException? ->

            }
            // Process the image
            facemesh!!.send(maskImage)
        }
    }

    private fun detectOriginFace() {

        executor!!.execute {
            facemesh!!.setResultListener { result: FaceMeshResult? ->
                originFace = result
                Log.e("GPUSwap", "Detect origin-face")

                detectMaskFace()
            }

            facemesh!!.setErrorListener { message: String, _: java.lang.RuntimeException? ->

            }
            // Process the image
            facemesh!!.send(originImage)
        }
    }

    private fun makeFaceMask() {

        makeFaceSwapFilter()

    }

    private fun makeFaceSwapFilter() {
        val mask_color = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)//s_gui_prop.mask_alpha];
        // if (s_is_dragover)
        //     mask_color = [0.8, 0.8, 0.8, 1.0];

        //for (i in 0 until originFace!!.multiFaceLandmarks().size)

            val keypoints = originFace!!.multiFaceLandmarks()[0].landmarkList

//            Log.e("GPUSwap", "ORIGIN-keypoints - SCALE_MESH::  $keypoints")

            /* render the deformed mask image onto the camera image */
            if (maskFace!!.multiFaceLandmarks().size > 0)
            {
                val mask_keypoints = maskFace!!.multiFaceLandmarks()[0].landmarkList;

                var face_vtx = FloatArray(keypoints.size * 3);
                var face_uv  = FloatArray(keypoints.size * 2);
                for ( i in 0 until keypoints.size)
                {
                    val p = keypoints[i]
                    face_vtx[3 * i + 0] = p.x //* scale + tx;
                    face_vtx[3 * i + 1] = p.y //* scale + ty;
                    face_vtx[3 * i + 2] = p.z

                    val q = mask_keypoints[i]
                    face_uv [2 * i + 0] = q.x /// masktex.image.width;
                    face_uv [2 * i + 1] = q.y /// masktex.image.height;


                }

                //let eye_hole = s_gui_prop.mask_eye_hole;
                //draw_facemesh_tri_tex (gl, masktex.texid, face_vtx, face_uv, mask_color, eye_hole, flip_h)
                //draw_facemesh_tri_tex (gl, masktex.texid, face_vtx, face_uv, mask_color, false, false)
                val filter = SwapFilter(face_vtx, face_uv, mask_color)


//                gpuImageView.filter = filter
//                gpuImageView.setImage(this.maskImage)
//                gpuImageView.requestRender()


//                gpuImage.setFilter(GPUImageSobelEdgeDetectionFilter())
//                gpuImage.requestRender()
                //gpuImage.setImage(this.maskImage)


                gpuImage.setFilter(filter)

                val img: Bitmap = gpuImage.getBitmapWithFilterApplied(this.maskImage)
//                //gpuImage.saveToPictures("GPUImage", "ImageWithFilter.jpg", null)
                Log.e("GPUSwap", "Origin ${originImage!!.height}  ${originImage!!.width}")
                Log.e("GPUSwap", "Result ${img!!.height}  ${img!!.width}")


                Log.e("GPUSwap", "Result ${img!!.height * 1.0 / originImage!!.height}  " +
                        "${img!!.width * 1.0 / originImage!!.width}")
                runOnUiThread {
                        imageView.setImageBitmap(img)
                    }


            }

    }

    companion object {
        private const val REQUEST_PICK_IMAGE = 1
    }
}
