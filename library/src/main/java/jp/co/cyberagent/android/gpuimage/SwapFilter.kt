package jp.co.cyberagent.android.gpuimage

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.util.OpenGlUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer


class SwapFilter(vertexShader: String?, fragmentShader: String?) :
    GPUImageFilter(vertexShader, fragmentShader) {

    companion object {

        const val NO_FILTER_VERTEX_SHADER = "" +
                "attribute vec4 position;\n" +
                "attribute vec4 inputTextureCoordinate;\n" +
                " \n" +
                "varying vec2 textureCoordinate;\n" +
                " \n" +
                "void main()\n" +
                "{\n" +
                "    gl_Position = position;\n" +
                "    textureCoordinate = inputTextureCoordinate.xy;\n" +
                "}"
        const val NO_FILTER_FRAGMENT_SHADER = "" +
                "varying highp vec2 textureCoordinate;\n" +
                " \n" +
                "uniform sampler2D inputImageTexture;\n" +
                " \n" +
                "void main()\n" +
                "{\n" +
                "     gl_FragColor = texture2D(inputImageTexture, textureCoordinate);\n" +
                "}"


    private val VERTEX_SHADER = """
        attribute vec4  a_Vertex;
        attribute vec4  a_TexCoord;
        uniform   mat4  u_PMVMatrix;
        varying   vec2  v_texcoord;

        void main(void)
        {
            v_texcoord = vec2(a_TexCoord.x, a_TexCoord.y); 
            //vec2((2.0*(a_TexCoord.x) - 1.0), (1.0 - 2.0*(a_TexCoord.y)));
            //v_texcoord  = a_TexCoord.xy;
            gl_Position = vec4((2.0*(a_Vertex.x) - 1.0), (1.0 - 2.0*(a_Vertex.y)), a_Vertex.z, a_Vertex.w);
            //u_PMVMatrix * vec4((2.0*(a_Vertex.x) - 1.0), (1.0 - 2.0*(a_Vertex.y)), a_Vertex.z, a_Vertex.w);
            //vec4((2.0*(a_TexCoord.x) - 1.0), (1.0 - 2.0*(a_TexCoord.y)), a_TexCoord.z, a_TexCoord.w);
            //vec4((2.0*(a_TexCoord.x) - 1.0), (1.0 - 2.0*(a_TexCoord.y)), a_TexCoord.z, a_TexCoord.w);
            //a_TexCoord;//u_PMVMatrix * a_Vertex;
        }
        """

    private val FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2    v_texcoord;
        uniform sampler2D u_sampler;

        void main(void)
        {
            gl_FragColor = texture2D(u_sampler, v_texcoord);
        }
        """

    val ADD_BLEND_FRAGMENT_SHADER = """
             varying highp vec2 textureCoordinate;
             varying highp vec2 textureCoordinate2;
            
             uniform sampler2D inputImageTexture;
             uniform sampler2D inputImageTexture2;
             
             void main()
             {
               lowp vec4 base = texture2D(inputImageTexture, textureCoordinate);
               lowp vec4 overlay = texture2D(inputImageTexture2, textureCoordinate2);
            
               mediump float r;
               if (overlay.r * base.a + base.r * overlay.a >= overlay.a * base.a) {
                 r = overlay.a * base.a + overlay.r * (1.0 - base.a) + base.r * (1.0 - overlay.a);
               } else {
                 r = overlay.r + base.r;
               }
            
               mediump float g;
               if (overlay.g * base.a + base.g * overlay.a >= overlay.a * base.a) {
                 g = overlay.a * base.a + overlay.g * (1.0 - base.a) + base.g * (1.0 - overlay.a);
               } else {
                 g = overlay.g + base.g;
               }
            
               mediump float b;
               if (overlay.b * base.a + base.b * overlay.a >= overlay.a * base.a) {
                 b = overlay.a * base.a + overlay.b * (1.0 - base.a) + base.b * (1.0 - overlay.a);
               } else {
                 b = overlay.b + base.b;
               }
            
               mediump float a = overlay.a + base.a - overlay.a * base.a;
               
               gl_FragColor = vec4(r, g, b, a);
             }"""

    private val ADD_BLEND_VERTEX_SHADER = """
            attribute vec4 position;
            attribute vec4 inputTextureCoordinate;
            attribute vec4 inputTextureCoordinate2;
             
            varying vec2 textureCoordinate;
            varying vec2 textureCoordinate2;
             
            void main()
            {
                gl_Position = position;
                textureCoordinate = inputTextureCoordinate.xy;
                textureCoordinate2 = inputTextureCoordinate2.xy;
            }"""

    }




    private var maskTexture = OpenGlUtils.NO_TEXTURE
    private var bitmap: Bitmap? = null


    private var loc_vtx:Int = 0
    private var loc_clr:Int = 0
    private var loc_nrm:Int = 0
    private var loc_uv:Int = 0
    private var loc_smp:Int = 0

    private var loc_vtxalpha:Int = 0
    private var loc_mtx_pmv:Int = 0
    private var loc_color:Int = 0
    private var loc_alpha:Int = 0

    var matPrj = floatArrayOf(
        0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f,
        -1f, 1f, 0f, 1f)


    var vbo_idx: ShortBuffer? = null
    var vbo_alpha: FloatBuffer? = null

    var vtx: FloatArray? = null // Mask-face vertex
    var uv: FloatArray? = null // Mask-face uniform value
    var color: FloatArray? = null // Color of mask - 4 value
    val drill_eye_hole = false
    val flip_h = false

    constructor(
        vtx: FloatArray, // Origin-face vertex
        uv: FloatArray, // Mask-face uniform value
        color: FloatArray // Color of mask - 4 value

    ) : this(VERTEX_SHADER, FRAGMENT_SHADER) {
        this.vtx = vtx
        this.uv = uv
        this.color = color

    }

    override fun onInit() {
        super.onInit()
        loc_vtx = GLES20.glGetAttribLocation (getProgram(), "a_Vertex")
        loc_clr = GLES20.glGetAttribLocation (getProgram(), "a_Color" )
        loc_nrm = GLES20.glGetAttribLocation (getProgram(), "a_Normal" )
        loc_uv = GLES20.glGetAttribLocation (getProgram(), "a_TexCoord")
        loc_smp = GLES20.glGetAttribLocation (getProgram(), "u_sampler")


        loc_vtxalpha= GLES20.glGetAttribLocation  (getProgram(), "a_vtxalpha");
        loc_mtx_pmv = GLES20.glGetUniformLocation (getProgram(), "u_PMVMatrix" );
        loc_color   = GLES20.glGetUniformLocation (getProgram(), "u_color" );
        loc_alpha   = GLES20.glGetUniformLocation (getProgram(), "u_alpha" );

        Log.e("GPUSwap", "vtx $loc_vtx  clr $loc_clr  nrm $loc_nrm  uv $loc_uv  " +
                "vtxalpha $loc_vtxalpha  " +
                "smp $loc_smp  mtx_pmv $loc_mtx_pmv  color $loc_color  alpha $loc_alpha")

    }

    override fun onInitialized() {
        super.onInitialized()
        if (bitmap != null && !bitmap!!.isRecycled) {
            setBitmap(bitmap)
        }
    }

    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap != null && bitmap.isRecycled) {
            return
        }
        this.bitmap = bitmap
        if (this.bitmap == null) {
            return
        }
        runOnDraw(Runnable {
            if (maskTexture == OpenGlUtils.NO_TEXTURE) {
                if (bitmap == null || bitmap.isRecycled) {
                    return@Runnable
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                maskTexture =
                    OpenGlUtils.loadTexture(bitmap, OpenGlUtils.NO_TEXTURE, false)
                Log.e("GPUSwap", "Mask-texture:: ${maskTexture}")
            }
        })
    }

    fun getBitmap(): Bitmap? {
        return bitmap
    }

    fun recycleBitmap() {
        if (bitmap != null && !bitmap!!.isRecycled) {
            bitmap!!.recycle()
            bitmap = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        GLES20.glDeleteTextures(
            1, intArrayOf(
                maskTexture
            ), 0
        )
        maskTexture = OpenGlUtils.NO_TEXTURE
    }

    override fun onDrawArraysPre() {
//        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
//        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
        
    }

    fun create_vbo_alpha_array (tris: ShortArray): FloatBuffer
    {
        /*
         *  Vertex indices are from:
         *      https://github.com/tensorflow/tfjs-models/blob/master/facemesh/src/keypoints.ts
         */
        val face_countour_idx = intArrayOf(
            10,  338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58,  132, 93,  234, 127, 162, 21,  54,  103, 67,  109
        )

        var vtx_counts = tris.size;
        var alpha_array = FloatArray(vtx_counts);

        for (i in 0 until vtx_counts)
        {
            var alpha = 1.0f;
            for (element in face_countour_idx)
            {
                if (i == element)
                {
                    alpha = 0.0f
                    break;
                }
            }
            alpha_array[i] = alpha;
        }

//            var vbo_alpha = GLES20.glCreateBuffer();
//            GLES20.glBindBuffer (GLES20.GL_ARRAY_BUFFER, vbo_alpha);
//            GLES20.glBufferData (GLES20.GL_ARRAY_BUFFER, FloatArray(alpha_array), GLES20.GL_STATIC_DRAW);
//
//            return vbo_alpha;
        var vbo_alpha = ByteBuffer
            .allocateDirect(alpha_array.size * Constants.BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        vbo_alpha.put(alpha_array)
        return vbo_alpha
    }

    override fun onDraw(
        textureId: Int, cubeBuffer: FloatBuffer,
        textureBuffer: FloatBuffer
    ) {
        GLES20.glUseProgram(this.program)
        runPendingOnDrawTasks()
        if (!isInitialized) {
            return
        }




        var vbo_vtx = ByteBuffer
            .allocateDirect(vtx!!.size * Constants.BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        vbo_vtx!!.put(vtx!!)

        vbo_vtx!!.position(0) // data start from 0
        GLES20.glVertexAttribPointer (loc_vtx, 3, GLES20.GL_FLOAT, false, 0, vbo_vtx!!);
        GLES20.glEnableVertexAttribArray(loc_vtx)

        // Set the camera position (View matrix)

        // Calculate the projection and view transformation




//        var matMV     = FloatArray(16) { 1f }//(16);
//        var matPMV    = FloatArray(16) { 1f }
//
//        matPrj = FloatArray(16)
//        val ratio: Float = this.outputWidth.toFloat() / this.outputHeight.toFloat()
//        Matrix.frustumM(matPrj, 0, -ratio, ratio, -1f, 1f, 3f, 7f)
//        Matrix.setLookAtM(matMV, 0, 0f, 0f, 6f, 0f, 0f, 0f, 0f, 2.0f, 0.0f)
//        Matrix.multiplyMV (matPMV, 0, matPrj, 0, matMV, 0)

        matPrj[0] =  (2.0 / this.outputWidth).toFloat();
        matPrj[5] = (-2.0 / this.outputHeight).toFloat();
        var matMV     = FloatArray(16) { 1f }//(16);
        var matPMV    = FloatArray(16) { 1f }
        Matrix.setIdentityM (matMV, 0)
        Matrix.multiplyMV (matPMV, 0, matPrj, 0, matMV, 0)

        GLES20.glUniformMatrix4fv (loc_mtx_pmv, 1, false, matPMV, 0)
//        GLES20.glUniformMatrix4fv (loc_mtx_pmv, 1, false, FloatBuffer.wrap(matPMV))


        var vbo_uv = ByteBuffer
            .allocateDirect(uv!!.size * Constants.BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        vbo_uv!!.put(uv!!)

        vbo_uv!!.position(0) // data start from 0
        GLES20.glVertexAttribPointer (
            loc_uv, 2, GLES20.GL_FLOAT, false, 0,
            vbo_uv!!);
        GLES20.glEnableVertexAttribArray(loc_uv)

        Log.e("GPUSwap", "Size ${uv!!.size} length ${vbo_uv!!.limit()}")


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(this.loc_smp, 0)


        //GLES20.glEnable (GLES20.GL_BLEND);
        GLES20.glEnable( GLES20.GL_DEPTH_TEST );
        GLES20.glDepthFunc( GLES20.GL_LEQUAL );
        GLES20.glDepthMask( true );

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val drawIndex = Constants.s_face_tris
        vbo_idx = initShortBuffer(drawIndex)


        vbo_idx!!.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            drawIndex.size,
            GLES20.GL_UNSIGNED_SHORT, vbo_idx!!);

        Log.e("GPUSwap", "Size ${drawIndex.size} length ${vbo_idx!!.capacity()}")


//        GLES20.glDisable (GLES20.GL_BLEND);
//        GLES20.glFrontFace (GLES20.GL_CCW);


    }

    fun initShortBuffer(data: ShortArray): ShortBuffer {
        val result = ByteBuffer
            .allocateDirect(data.size * Constants.BYTES_PER_SHORT)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()

        result.put(data)
        return result
    }

    
    fun onDrawFace(
        textureId: Int, cubeBuffer: FloatBuffer,
        textureBuffer: FloatBuffer
    ) {

        Log.e("GPUSwap", "origin-texture:: ${textureId}")

        GLES20.glUseProgram(this.program)
        runPendingOnDrawTasks()
        if (!isInitialized) {
            return
        }


        matPrj[0] =  (2.0 / this.outputWidth).toFloat();
        matPrj[5] = (-2.0 / this.outputHeight).toFloat();


        vbo_idx = ByteBuffer
            .allocateDirect(Constants.s_face_tris.size * Constants.BYTES_PER_SHORT)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()

        vbo_idx!!.put(Constants.s_face_tris)




        vbo_alpha  = create_vbo_alpha_array (Constants.s_face_tris);






        GLES20.glEnable (GLES20.GL_CULL_FACE);
        if (flip_h)
            GLES20.glFrontFace (GLES20.GL_CW);

        GLES20.glUseProgram (this.program);


        var vbo_vtx = ByteBuffer
            .allocateDirect(vtx!!.size * Constants.BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        vbo_vtx!!.put(vtx!!)

        vbo_vtx!!.position(0) // data start from 0
        GLES20.glVertexAttribPointer (loc_vtx, 3, GLES20.GL_FLOAT, false, 0, vbo_vtx!!);
        GLES20.glEnableVertexAttribArray(loc_vtx)


        var vbo_uv = ByteBuffer
            .allocateDirect(uv!!.size * Constants.BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        vbo_uv!!.put(uv!!)

        vbo_uv!!.position(0) // data start from 0
        GLES20.glVertexAttribPointer (loc_uv, 2, GLES20.GL_FLOAT, false, 0, vbo_uv!!);
        GLES20.glEnableVertexAttribArray(loc_uv)





//                GLES20.glBindBuffer (GLES20.GL_ARRAY_BUFFER, vbo_alpha);
        vbo_alpha!!.position(0)
        GLES20.glVertexAttribPointer (
            loc_vtxalpha, 1, GLES20.GL_FLOAT, false, 0, vbo_alpha);


        var matMV     = FloatArray(16) { 1f }//(16);
        var matPMV    = FloatArray(16) { 1f }
        Matrix.setIdentityM (matMV, 0)
        Matrix.multiplyMV (matPMV, 0, matPrj, 0, matMV, 0)
        GLES20.glUniformMatrix4fv (loc_mtx_pmv, 1, false, FloatBuffer.wrap(matPMV))

        GLES20.glUniform3f (loc_color, color!![0], color!![1], color!![2]);
        GLES20.glUniform1f (loc_alpha, color!![3]);

        GLES20.glEnable (GLES20.GL_BLEND);


        Log.e("GPUSwap", "vtx $loc_vtx  clr $loc_clr  nrm $loc_nrm  uv $loc_uv  " +
                "vtxalpha $loc_vtxalpha  " +
                "smp $loc_smp  mtx_pmv $loc_mtx_pmv  color $loc_color  alpha $loc_alpha")



//        GLES20.glBindTexture (GLES20.GL_TEXTURE_2D, maskTexture);
//        GLES20.glUniform1i(loc_smp, 0)

//        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
//        GLES20.glUniform1i(loc_smp, 0)


        //GLES20.gldrawElements (GLES20.glTRIANGLES, vtx_counts, GLES20.glUNSIGNED_SHORT, 0);
        vbo_idx!!.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            Constants.s_face_tris.size, GLES20.GL_UNSIGNED_SHORT, vbo_idx!!);



        Log.e("GPUSwap", "vtx $loc_vtx  clr $loc_clr  nrm $loc_nrm  uv $loc_uv  " +
                "vtxalpha $loc_vtxalpha  " +
                "smp $loc_smp  mtx_pmv $loc_mtx_pmv  color $loc_color  alpha $loc_alpha")


        GLES20.glDisable (GLES20.GL_BLEND);
        GLES20.glFrontFace (GLES20.GL_CCW);
    }


}
