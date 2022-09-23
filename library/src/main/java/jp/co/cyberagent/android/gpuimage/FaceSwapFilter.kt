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


class FaceSwapFilter(vertexShader: String?, fragmentShader: String?) :
    GPUImageFilter(vertexShader, fragmentShader) {

    companion object {


        private val VERTEX_SHADER = """
            attribute vec4  a_Vertex;
            attribute vec4  a_TexCoord;
            uniform   mat4  u_PMVMatrix;
            varying   vec2  v_texcoord;
    
            void main(void)
            {
                v_texcoord = vec2(a_TexCoord.x, a_TexCoord.y); 
                gl_Position = vec4((2.0*(a_Vertex.x) - 1.0), (1.0 - 2.0*(a_Vertex.y)), a_Vertex.z, a_Vertex.w);
    
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

    }




    private var loc_vtx:Int = 0
    private var loc_clr:Int = 0
    private var loc_nrm:Int = 0
    private var loc_uv:Int = 0
    private var loc_smp:Int = 0

    private var loc_vtxalpha:Int = 0
    private var loc_mtx_pmv:Int = 0
    private var loc_color:Int = 0
    private var loc_alpha:Int = 0



    var vbo_idx: ShortBuffer? = null

    var vtx: FloatArray? = null // Mask-face vertex
    var uv: FloatArray? = null // Mask-face uniform value
    var color: FloatArray? = null // Color of mask - 4 value
    val drill_eye_hole = false

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







    override fun onDestroy() {
        super.onDestroy()
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

        val drawIndex = Constants.s_face_tris
        vbo_idx = initShortBuffer(drawIndex)


        vbo_idx!!.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            drawIndex.size,
            GLES20.GL_UNSIGNED_SHORT, vbo_idx!!);

        Log.e("GPUSwap", "Size ${drawIndex.size} length ${vbo_idx!!.capacity()}")


    }

    fun initShortBuffer(data: ShortArray): ShortBuffer {
        val result = ByteBuffer
            .allocateDirect(data.size * Constants.BYTES_PER_SHORT)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()

        result.put(data)
        return result
    }

}
