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
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import jp.co.cyberagent.android.gpuimage.GPUImageView
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.FilterAdjuster
import jp.co.cyberagent.android.gpuimage.sample.R

/**
 * 1. Static image editing with OpenGL starts here
 */
class GalleryActivity : AppCompatActivity() {

    // 1. Some filters have config to adjust
    private var filterAdjuster: FilterAdjuster? = null

    // 2. Main OpenGL-based imageview - for showing image and applied OpenGL-based filters
    private val gpuImageView: GPUImageView by lazy { findViewById<GPUImageView>(R.id.gpuimage) }

    // 3. Seekbar to adjust filter config values
    private val seekBar: SeekBar by lazy { findViewById<SeekBar>(R.id.seekBar) }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        // 1.1 Initialize seekBar
        seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.i("GalleryOpenGL", "Adjust filter with new value: $progress")
                filterAdjuster?.adjust(progress)
                // Request openGL-view re-render with new config
                gpuImageView.requestRender()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 1.2. Initialize filter choices
        findViewById<View>(R.id.button_choose_filter).setOnClickListener {
            Log.i("GalleryOpenGL", "Show filter list")
            GPUImageFilterTools.showDialog(this) { filter ->
                Log.i("GalleryOpenGL", "Click on filter: $filter")
                // Switch to new filter
                switchFilterTo(filter)
                // Request openGL re-render with new filter
                gpuImageView.requestRender()
            }
        }

        // 1.3. Initialize saving button
        findViewById<View>(R.id.button_save).setOnClickListener {
            Log.i("GalleryOpenGL", "Save image from openGL-based view")
            saveImage()
        }

        // 1.4. Start photo-picker
        startPhotoPicker()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_PICK_IMAGE -> if (resultCode == RESULT_OK) {
                gpuImageView.setImage(data!!.data)
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

    private fun saveImage() {
        val fileName = System.currentTimeMillis().toString() + ".jpg"
        gpuImageView.saveToPictures("GPUImage", fileName) { uri ->
            Toast.makeText(this, "Saved: " + uri.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchFilterTo(filter: GPUImageFilter) {
        if (gpuImageView.filter == null || gpuImageView.filter.javaClass != filter.javaClass) {
            // 1. Set new filter to openGL-based view's filter
            gpuImageView.filter = filter

            // 2. Update new filter adjuster and seekbar if needed
            filterAdjuster = FilterAdjuster(filter)
            if (filterAdjuster!!.canAdjust()) {
                seekBar.visibility = View.VISIBLE
                filterAdjuster!!.adjust(seekBar.progress)
            } else {
                seekBar.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val REQUEST_PICK_IMAGE = 1
    }
}
